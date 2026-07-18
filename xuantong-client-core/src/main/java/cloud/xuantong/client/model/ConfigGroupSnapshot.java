package cloud.xuantong.client.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoritative decision and event watermarks for one Config State Group. */
public record ConfigGroupSnapshot(
        long eventRevision,
        long compactionRevision,
        Map<String, Long> decisionRevisions) {

    public ConfigGroupSnapshot {
        decisionRevisions = Collections.unmodifiableMap(
                new LinkedHashMap<>(decisionRevisions == null ? Map.of() : decisionRevisions));
    }
}
