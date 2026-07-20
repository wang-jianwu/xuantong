package cloud.xuantong.common.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedLatencyHistogramTest {
    @Test
    void recordsCumulativeBucketsAndKeepsOverflowInCount() {
        FixedLatencyHistogram histogram = new FixedLatencyHistogram(5, 10);

        histogram.recordMillis(1);
        histogram.recordMillis(6);
        histogram.recordMillis(12);

        FixedLatencyHistogram.Snapshot snapshot = histogram.snapshot();
        assertEquals(3L, snapshot.count());
        assertEquals(0.019D, snapshot.totalSeconds(), 0.000001D);
        assertArrayEquals(new long[]{5, 10}, snapshot.upperBoundsMillis());
        assertArrayEquals(new long[]{1, 2}, snapshot.cumulativeCounts());
    }

    @Test
    void rejectsUnorderedBuckets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedLatencyHistogram(10, 10));
    }
}
