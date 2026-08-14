package com.fix42.oms.persist;

import com.fix42.oms.proto.CacheSnapshot;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotStoreTest {

    @TempDir
    Path dir;

    private static CacheSnapshot snapshot(long seq) {
        return CacheSnapshot.newBuilder()
                .setLastAppliedSequence(seq)
                .setCreatedEpochMillis(1_000 + seq)
                .setChainSeq(seq)
                .putStates("chain-" + seq, OrderState.newBuilder().setOrderId("OID" + seq).build())
                .build();
    }

    @Test
    void writeThenLoadRoundTrips() throws IOException {
        SnapshotStore.write(dir, snapshot(5), 2);
        CacheSnapshot loaded = SnapshotStore.loadLatest(dir);
        assertEquals(snapshot(5), loaded);
    }

    @Test
    void loadPicksNewestOfSeveral() throws IOException {
        SnapshotStore.write(dir, snapshot(3), 5);
        SnapshotStore.write(dir, snapshot(9), 5);
        SnapshotStore.write(dir, snapshot(6), 5);
        assertEquals(9, SnapshotStore.loadLatest(dir).getLastAppliedSequence());
    }

    @Test
    void pruneKeepsOnlyNewestN() throws IOException {
        SnapshotStore.write(dir, snapshot(1), 2);
        SnapshotStore.write(dir, snapshot(2), 2);
        SnapshotStore.write(dir, snapshot(3), 2);
        try (Stream<Path> files = Files.list(dir)) {
            long count = files.filter(p -> p.getFileName().toString().endsWith(SnapshotStore.SUFFIX)).count();
            assertEquals(2, count);
        }
        assertEquals(3, SnapshotStore.loadLatest(dir).getLastAppliedSequence());
        assertEquals(2, SnapshotStore.oldestValidSequence(dir));
    }

    @Test
    void corruptNewestFallsBackToOlderGeneration() throws IOException {
        SnapshotStore.write(dir, snapshot(4), 5);
        Path newest = SnapshotStore.write(dir, snapshot(8), 5);

        byte[] bytes = Files.readAllBytes(newest);
        bytes[bytes.length / 2] ^= (byte) 0xFF; // corrupt the payload
        Files.write(newest, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        assertEquals(4, SnapshotStore.loadLatest(dir).getLastAppliedSequence());
    }

    @Test
    void ignoresLeftoverTmpFiles() throws IOException {
        Files.write(dir.resolve(SnapshotStore.PREFIX + "00000000000000000009" + SnapshotStore.SUFFIX + ".tmp"),
                new byte[]{1, 2, 3});
        assertNull(SnapshotStore.loadLatest(dir));
        SnapshotStore.write(dir, snapshot(2), 2);
        assertEquals(2, SnapshotStore.loadLatest(dir).getLastAppliedSequence());
    }

    @Test
    void emptyDirLoadsNull() throws IOException {
        assertNull(SnapshotStore.loadLatest(dir));
        assertEquals(0, SnapshotStore.oldestValidSequence(dir));
    }
}
