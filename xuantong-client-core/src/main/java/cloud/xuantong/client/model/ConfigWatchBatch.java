package cloud.xuantong.client.model;

import java.util.List;

/** One authoritative, resumable Config State Watch batch. */
public record ConfigWatchBatch(
        long requestedAfterRevision,
        long coveredThroughRevision,
        long compactionRevision,
        boolean resetRequired,
        List<ConfigInvalidation> events) {

    public ConfigWatchBatch {
        events = List.copyOf(events == null ? List.of() : events);
    }
}
