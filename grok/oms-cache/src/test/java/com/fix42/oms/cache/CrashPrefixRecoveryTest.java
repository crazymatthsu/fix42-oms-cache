package com.fix42.oms.cache;

import com.fix42.oms.persist.FsyncMode;
import com.fix42.oms.persist.PersistenceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CrashPrefixRecoveryTest {

    @TempDir
    Path root;

    @Test
    void eachForcedPrefixRecoversToThatPrefix() throws Exception {
        Path liveDir = root.resolve("live");
        Files.createDirectories(liveDir);
        CacheConfig liveConfig = PersistenceRecoveryTest.persistentConfig(liveDir, 1_000);
        String[] tape = PersistenceRecoveryTest.tape();

        try (InMemoryOrderCache live = InMemoryOrderCache.recover(liveConfig).orderCache()) {
            for (int i = 0; i < tape.length; i++) {
                live.ingest(tape[i]);
                Path copy = root.resolve("copy-" + i);
                copyDir(liveDir, copy);

                InMemoryOrderCache expected = InMemoryOrderCache.create();
                for (int j = 0; j <= i; j++) {
                    expected.ingest(tape[j]);
                }

                CacheConfig copyConfig = PersistenceRecoveryTest.persistentConfig(copy, 1_000);
                try (InMemoryOrderCache recovered = InMemoryOrderCache.recover(copyConfig).orderCache()) {
                    assertThat(PersistenceRecoveryTest.normalized(recovered.snapshot()))
                            .as("prefix %s", i)
                            .containsExactlyInAnyOrderElementsOf(
                                    PersistenceRecoveryTest.normalized(expected.snapshot()));
                    assertThat(recovered.size()).isEqualTo(expected.size());
                }
            }
        }
    }

    @Test
    void recoverAfterGroupFsyncStillReadsForcedPrefix() {
        Path dir = root.resolve("group");
        CacheConfig config = CacheConfig.builder()
                .persistence(PersistenceConfig.builder()
                        .dataDir(dir)
                        .fsync(FsyncMode.GROUP)
                        .groupForceEveryMessages(2)
                        .groupForceEvery(Duration.ofHours(1))
                        .snapshotEveryMessages(100)
                        .snapshotEvery(Duration.ZERO)
                        .build())
                .build();
        String[] tape = PersistenceRecoveryTest.tape();
        try (InMemoryOrderCache cache = InMemoryOrderCache.recover(config).orderCache()) {
            cache.ingest(tape[0]);
            cache.ingest(tape[1]);
        }
        try (InMemoryOrderCache recovered = InMemoryOrderCache.recover(config).orderCache()) {
            assertThat(recovered.size()).isEqualTo(2);
            assertThat(recovered.getByClOrdId("C1")).isPresent();
        }
    }

    private static void copyDir(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path dest = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
