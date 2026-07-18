package cloud.xuantong.client.model;

import java.util.List;

public record ServiceWatchBatch(
        long requestedAfterRevision,
        long coveredThroughRevision,
        long compactionRevision,
        boolean resetRequired,
        List<ServiceInvalidation> events) {

    public ServiceWatchBatch {
        if (requestedAfterRevision < 0
                || coveredThroughRevision < requestedAfterRevision
                || compactionRevision < 0
                || compactionRevision > coveredThroughRevision) {
            throw new IllegalArgumentException("Discovery Watch watermarks are invalid");
        }
        events = List.copyOf(events == null ? List.of() : events);
    }
}
