package com.fix42.oms.persist;

import com.fix42.oms.proto.Snapshot;
import com.fix42.oms.proto.WalRecord;

import java.util.List;
import java.util.Optional;

public final class RecoveryImage {
    private final Snapshot snapshot;
    private final List<WalRecord> walTail;
    private final long lastSeq;
    private final String sourceCursor;

    public RecoveryImage(Snapshot snapshot, List<WalRecord> walTail, long lastSeq, String sourceCursor) {
        this.snapshot = snapshot;
        this.walTail = List.copyOf(walTail);
        this.lastSeq = lastSeq;
        this.sourceCursor = sourceCursor;
    }

    public Optional<Snapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public List<WalRecord> walTail() {
        return walTail;
    }

    public long lastSeq() {
        return lastSeq;
    }

    public Optional<String> sourceCursor() {
        return Optional.ofNullable(sourceCursor);
    }
}
