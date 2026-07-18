package cloud.xuantong.state.api;

import java.util.List;

public record WatchBatch(
        StateRevision requestedAfter,
        StateRevision coveredThrough,
        StateRevision compactionRevision,
        boolean resetRequired,
        List<WatchEvent> events) {

    public WatchBatch {
        requireWatchCursor("requestedAfter", requestedAfter);
        requireSameCoordinate("coveredThrough", requestedAfter, coveredThrough);
        requireSameCoordinate("compactionRevision", requestedAfter, compactionRevision);
        if (coveredThrough.value() < requestedAfter.value()) {
            throw new IllegalArgumentException(
                    "coveredThrough must not be behind requestedAfter");
        }
        if (compactionRevision.value() > coveredThrough.value()) {
            throw new IllegalArgumentException(
                    "compactionRevision must not exceed coveredThrough");
        }
        boolean cursorCompacted = requestedAfter.value() < compactionRevision.value();
        if (resetRequired != cursorCompacted) {
            throw new IllegalArgumentException(
                    "resetRequired must exactly represent a compacted cursor");
        }
        events = List.copyOf(events == null ? List.of() : events);
        long previous = requestedAfter.value();
        for (WatchEvent event : events) {
            requireSameCoordinate("event revision", requestedAfter, event.revision());
            long eventRevision = event.revision().value();
            if (eventRevision <= previous) {
                throw new IllegalArgumentException(
                        "Watch events must be strictly ordered after requestedAfter");
            }
            if (eventRevision > coveredThrough.value()) {
                throw new IllegalArgumentException(
                        "Watch event exceeds coveredThrough revision");
            }
            previous = eventRevision;
        }
        if (resetRequired && !events.isEmpty()) {
            throw new IllegalArgumentException(
                    "A reset-required batch must not contain delta events");
        }
    }

    public StateGroupId groupId() {
        return requestedAfter.groupId();
    }

    private static void requireWatchCursor(String field, StateRevision revision) {
        if (revision == null || !revision.type().isWatchCursor()) {
            throw new IllegalArgumentException(
                    field + " must be a CONFIG_EVENT or REGISTRY cursor");
        }
    }

    private static void requireSameCoordinate(
            String field, StateRevision expected, StateRevision actual) {
        requireWatchCursor(field, actual);
        if (!expected.sameCoordinate(actual)) {
            throw new IllegalArgumentException(field + " uses another Watch coordinate");
        }
    }
}
