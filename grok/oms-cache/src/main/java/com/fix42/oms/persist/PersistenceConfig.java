package com.fix42.oms.persist;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class PersistenceConfig {
    private final Path dataDir;
    private final FsyncMode fsync;
    private final int snapshotEveryMessages;
    private final Duration snapshotEvery;
    private final int groupForceEveryMessages;
    private final Duration groupForceEvery;
    private final boolean durableIngest;

    private PersistenceConfig(Builder builder) {
        this.dataDir = builder.dataDir;
        this.fsync = builder.fsync;
        this.snapshotEveryMessages = builder.snapshotEveryMessages;
        this.snapshotEvery = builder.snapshotEvery;
        this.groupForceEveryMessages = builder.groupForceEveryMessages;
        this.groupForceEvery = builder.groupForceEvery;
        this.durableIngest = builder.durableIngest;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path dataDir() {
        return dataDir;
    }

    public FsyncMode fsync() {
        return fsync;
    }

    public int snapshotEveryMessages() {
        return snapshotEveryMessages;
    }

    public Duration snapshotEvery() {
        return snapshotEvery;
    }

    public int groupForceEveryMessages() {
        return groupForceEveryMessages;
    }

    public Duration groupForceEvery() {
        return groupForceEvery;
    }

    /**
     * When true (default), the WAL is forced before the in-memory apply.
     * When false, apply first (only safe if the source can rewind).
     */
    public boolean durableIngest() {
        return durableIngest;
    }

    public static final class Builder {
        private Path dataDir;
        private FsyncMode fsync = FsyncMode.EVERY_RECORD;
        private int snapshotEveryMessages = 1_000;
        private Duration snapshotEvery = Duration.ofSeconds(5);
        private int groupForceEveryMessages = 32;
        private Duration groupForceEvery = Duration.ofMillis(50);
        private boolean durableIngest = true;

        public Builder dataDir(Path dataDir) {
            this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
            return this;
        }

        public Builder fsync(FsyncMode fsync) {
            this.fsync = Objects.requireNonNull(fsync, "fsync");
            return this;
        }

        public Builder snapshotEveryMessages(int snapshotEveryMessages) {
            if (snapshotEveryMessages < 1) {
                throw new IllegalArgumentException("snapshotEveryMessages must be >= 1");
            }
            this.snapshotEveryMessages = snapshotEveryMessages;
            return this;
        }

        public Builder snapshotEvery(Duration snapshotEvery) {
            this.snapshotEvery = Objects.requireNonNull(snapshotEvery, "snapshotEvery");
            if (snapshotEvery.isNegative()) {
                throw new IllegalArgumentException("snapshotEvery must be >= 0");
            }
            return this;
        }

        public Builder groupForceEveryMessages(int groupForceEveryMessages) {
            if (groupForceEveryMessages < 1) {
                throw new IllegalArgumentException("groupForceEveryMessages must be >= 1");
            }
            this.groupForceEveryMessages = groupForceEveryMessages;
            return this;
        }

        public Builder groupForceEvery(Duration groupForceEvery) {
            this.groupForceEvery = Objects.requireNonNull(groupForceEvery, "groupForceEvery");
            return this;
        }

        public Builder durableIngest(boolean durableIngest) {
            this.durableIngest = durableIngest;
            return this;
        }

        public PersistenceConfig build() {
            if (dataDir == null) {
                throw new IllegalStateException("dataDir is required");
            }
            return new PersistenceConfig(this);
        }
    }
}
