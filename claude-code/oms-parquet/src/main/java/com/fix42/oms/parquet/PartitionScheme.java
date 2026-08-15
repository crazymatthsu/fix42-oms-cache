package com.fix42.oms.parquet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps a {@link PartitionKey} to the directory path a Parquet file is written under,
 * relative to {@code <archiveRoot>/<dataset>/}.
 *
 * <p>The default is the layout this archive was specified against —
 * {@code YYYY/MM/DD/account/symbol} — and every alternative is expressed as a
 * {@link #template(String) template} over the same tokens, so a deployment can pick a
 * different layout without writing code:
 *
 * <table border="1">
 *   <caption>Template tokens</caption>
 *   <tr><th>Token</th><th>Renders</th></tr>
 *   <tr><td>{@code {yyyy}}</td><td>4-digit year</td></tr>
 *   <tr><td>{@code {MM}}</td><td>2-digit month</td></tr>
 *   <tr><td>{@code {dd}}</td><td>2-digit day of month</td></tr>
 *   <tr><td>{@code {date}}</td><td>ISO date, {@code 2026-08-14}</td></tr>
 *   <tr><td>{@code {account}}</td><td>sanitised tag 1</td></tr>
 *   <tr><td>{@code {symbol}}</td><td>sanitised tag 55</td></tr>
 * </table>
 *
 * <p><b>Which layout is "better" depends on the reader.</b> The plain
 * {@code YYYY/MM/DD/...} form is the most portable and is what a directory-glob reader
 * (including a Deephaven intraday watcher pointed at a day's directory) wants.
 * {@link #hiveDateAccountSymbol()} and {@link #hiveYearMonthDayAccountSymbol()} emit
 * {@code key=value} segments, which DuckDB, Spark and Trino turn into free, indexed
 * partition columns with {@code hive_partitioning=1} — worth it when queries routinely
 * filter by account or symbol across many days. Both are supported; neither is forced.
 *
 * <p><b>Cardinality warning.</b> Partitioning by account <em>and</em> symbol multiplies
 * directory count by (accounts x symbols) per day. That is excellent for point queries
 * ("this account's IBM orders") and poor for wide scans, which end up opening many tiny
 * files — which is exactly what {@link ParquetCompactor end-of-day compaction} is for.
 * A high-cardinality deployment can drop to {@link #dateOnly()} or
 * {@code template("{yyyy}/{MM}/{dd}/{symbol}")} instead; the {@code account}/{@code symbol}
 * columns are inside every file either way, so no query loses the ability to filter.
 */
public interface PartitionScheme {

    /** Directory for {@code key}, relative to the dataset root; {@code /}-separated, no leading slash. */
    String relativeDirectory(PartitionKey key);

    /**
     * The leading path prefix shared by every partition of {@code date}, used to scope
     * end-of-day compaction and upload to one trading day. Returns {@code ""} when the
     * scheme does not start with date segments, which makes those jobs walk the whole
     * dataset instead of one day's subtree.
     */
    String datePrefix(LocalDate date);

    /** Human-readable description, e.g. the template string. */
    String describe();

    /** The specified default: {@code YYYY/MM/DD/account/symbol}. */
    static PartitionScheme dateAccountSymbol() {
        return template("{yyyy}/{MM}/{dd}/{account}/{symbol}");
    }

    /** {@code YYYY/MM/DD/symbol/account} — better when most queries lead with the symbol. */
    static PartitionScheme dateSymbolAccount() {
        return template("{yyyy}/{MM}/{dd}/{symbol}/{account}");
    }

    /** {@code date=YYYY-MM-DD/account=.../symbol=...} — Hive-style, one date segment. */
    static PartitionScheme hiveDateAccountSymbol() {
        return template("date={date}/account={account}/symbol={symbol}");
    }

    /** {@code year=YYYY/month=MM/day=DD/account=.../symbol=...} — Hive-style, split date. */
    static PartitionScheme hiveYearMonthDayAccountSymbol() {
        return template("year={yyyy}/month={MM}/day={dd}/account={account}/symbol={symbol}");
    }

    /** {@code YYYY/MM/DD} — one directory per day; fewest, largest files. */
    static PartitionScheme dateOnly() {
        return template("{yyyy}/{MM}/{dd}");
    }

    /**
     * A scheme from a slash-separated template of the tokens documented above.
     *
     * @throws IllegalArgumentException if the template is blank, absolute, contains an
     *                                  unknown {@code {token}}, or has no segment at all
     */
    static PartitionScheme template(String template) {
        return new TemplatePartitionScheme(template);
    }

    /** Template-driven {@link PartitionScheme}; see {@link PartitionScheme#template(String)}. */
    final class TemplatePartitionScheme implements PartitionScheme {

        private static final List<String> TOKENS = List.of("{yyyy}", "{MM}", "{dd}", "{date}", "{account}", "{symbol}");

        private final String template;
        private final List<String> segments;
        /** Count of leading segments that reference only date tokens — the compaction prefix. */
        private final int dateSegmentCount;

        TemplatePartitionScheme(String template) {
            if (template == null || template.isBlank()) {
                throw new IllegalArgumentException("Partition template must not be blank");
            }
            String normalised = template.trim().replace('\\', '/');
            while (normalised.startsWith("/")) {
                normalised = normalised.substring(1);
            }
            while (normalised.endsWith("/")) {
                normalised = normalised.substring(0, normalised.length() - 1);
            }
            List<String> segs = new ArrayList<>();
            for (String s : normalised.split("/")) {
                if (!s.isEmpty()) {
                    segs.add(s);
                }
            }
            if (segs.isEmpty()) {
                throw new IllegalArgumentException("Partition template has no segments: " + template);
            }
            for (String s : segs) {
                checkTokens(s, template);
            }
            this.template = normalised;
            this.segments = List.copyOf(segs);

            int dateSegs = 0;
            for (String s : segs) {
                if (s.contains("{account}") || s.contains("{symbol}")) {
                    break;
                }
                dateSegs++;
            }
            this.dateSegmentCount = dateSegs;
        }

        /** Reject unknown {@code {...}} placeholders at construction rather than emitting them literally. */
        private static void checkTokens(String segment, String template) {
            int i = 0;
            while ((i = segment.indexOf('{', i)) >= 0) {
                int end = segment.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed '{' in partition template: " + template);
                }
                String token = segment.substring(i, end + 1);
                if (!TOKENS.contains(token)) {
                    throw new IllegalArgumentException("Unknown token " + token + " in partition template: "
                            + template + " (supported: " + TOKENS + ")");
                }
                i = end + 1;
            }
        }

        @Override
        public String relativeDirectory(PartitionKey key) {
            StringBuilder sb = new StringBuilder();
            for (String segment : segments) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(render(segment, key.date(),
                        ParquetPaths.sanitizeSegment(key.account()),
                        ParquetPaths.sanitizeSegment(key.symbol())));
            }
            return sb.toString();
        }

        @Override
        public String datePrefix(LocalDate date) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dateSegmentCount; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(render(segments.get(i), date, "", ""));
            }
            return sb.toString();
        }

        @Override
        public String describe() {
            return template;
        }

        @Override
        public String toString() {
            return "PartitionScheme[" + template + "]";
        }

        private static String render(String segment, LocalDate date, String account, String symbol) {
            return segment
                    .replace("{yyyy}", String.format(Locale.ROOT, "%04d", date.getYear()))
                    .replace("{MM}", String.format(Locale.ROOT, "%02d", date.getMonthValue()))
                    .replace("{dd}", String.format(Locale.ROOT, "%02d", date.getDayOfMonth()))
                    .replace("{date}", date.toString())
                    .replace("{account}", account)
                    .replace("{symbol}", symbol);
        }
    }
}
