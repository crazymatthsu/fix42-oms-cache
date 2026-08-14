package com.fix42.oms.persist;

import com.fix42.oms.proto.Snapshot;
import com.fix42.oms.proto.WalRecord;

import java.io.Closeable;

public interface StateStore extends Closeable {
    long append(WalRecord record);

    void checkpoint(Snapshot snapshot);

    RecoveryImage recover();

    long lastSeq();

    @Override
    void close();
}
