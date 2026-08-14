package com.fix42.oms.persist;

import com.fix42.oms.proto.PersistedOrder;
import com.fix42.oms.proto.Snapshot;
import com.fix42.oms.proto.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileStateStoreTest {

    @TempDir
    Path dir;

    @Test
    void appendAndRecoverRoundTrip() {
        PersistenceConfig config = PersistenceConfig.builder().dataDir(dir).fsync(FsyncMode.EVERY_RECORD).build();
        try (FileStateStore store = new FileStateStore(config)) {
            store.append(WalRecord.newBuilder().setRawFix("A").setMsgType("D").build());
            store.append(WalRecord.newBuilder().setRawFix("B").setMsgType("8").setSourceCursor("off=2").build());
            RecoveryImage image = store.recover();
            assertThat(image.walTail()).hasSize(2);
            assertThat(image.lastSeq()).isEqualTo(2);
            assertThat(image.sourceCursor()).contains("off=2");
        }
    }

    @Test
    void tornTailIsIgnoredOnReopen() throws Exception {
        PersistenceConfig config = PersistenceConfig.builder().dataDir(dir).fsync(FsyncMode.EVERY_RECORD).build();
        try (FileStateStore store = new FileStateStore(config)) {
            store.append(WalRecord.newBuilder().setRawFix("one").setMsgType("D").build());
            store.append(WalRecord.newBuilder().setRawFix("two").setMsgType("8").build());
            store.append(WalRecord.newBuilder().setRawFix("three").setMsgType("8").build());
        }
        Path wal = Files.list(dir.resolve("wal")).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(wal);
        Files.write(wal, java.util.Arrays.copyOf(bytes, bytes.length - 5), StandardOpenOption.TRUNCATE_EXISTING);

        try (FileStateStore store = new FileStateStore(config)) {
            RecoveryImage image = store.recover();
            assertThat(image.walTail()).hasSize(2);
            assertThat(image.walTail().get(1).getRawFix()).isEqualTo("two");
            long seq = store.append(WalRecord.newBuilder().setRawFix("four").setMsgType("8").build());
            assertThat(seq).isEqualTo(3);
        }
    }

    @Test
    void snapshotSkipsCoveredWalOnRecover() {
        PersistenceConfig config = PersistenceConfig.builder().dataDir(dir).fsync(FsyncMode.NONE).build();
        try (FileStateStore store = new FileStateStore(config)) {
            store.append(WalRecord.newBuilder().setRawFix("A").setMsgType("D").build());
            store.append(WalRecord.newBuilder().setRawFix("B").setMsgType("8").setSourceCursor("c1").build());
            store.checkpoint(Snapshot.newBuilder()
                    .setUpToSeq(2)
                    .setSourceCursor("c1")
                    .addOrders(PersistedOrder.newBuilder())
                    .build());
            store.append(WalRecord.newBuilder().setRawFix("C").setMsgType("8").setSourceCursor("c2").build());
        }
        try (FileStateStore store = new FileStateStore(config)) {
            RecoveryImage image = store.recover();
            assertThat(image.snapshot()).isPresent();
            assertThat(image.snapshot().orElseThrow().getUpToSeq()).isEqualTo(2);
            assertThat(image.walTail()).hasSize(1);
            assertThat(image.walTail().get(0).getRawFix()).isEqualTo("C");
            assertThat(image.sourceCursor()).contains("c2");
        }
    }

    @Test
    void compactionRemovesCoveredWalSegments() throws Exception {
        PersistenceConfig config = PersistenceConfig.builder().dataDir(dir).fsync(FsyncMode.EVERY_RECORD).build();
        try (FileStateStore store = new FileStateStore(config)) {
            store.append(WalRecord.newBuilder().setRawFix("A").setMsgType("D").build());
            store.checkpoint(Snapshot.newBuilder().setUpToSeq(1).build());
        }
        List<Path> remaining = Files.list(dir.resolve("wal")).toList();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getFileName().toString()).isNotEqualTo("wal-0000000001.log");
    }
}
