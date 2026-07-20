package cloud.xuantong.probe;

import cloud.xuantong.client.ControlPlaneProbeResult;

record ProbeObservation(
        boolean successful,
        long durationNanos,
        long completedAtEpochMs,
        ControlPlaneProbeResult result,
        String failureCategory) {

    static ProbeObservation success(
            long durationNanos,
            long completedAtEpochMs,
            ControlPlaneProbeResult result) {
        return new ProbeObservation(
                true, Math.max(0L, durationNanos), completedAtEpochMs,
                result, "none");
    }

    static ProbeObservation failure(
            long durationNanos,
            long completedAtEpochMs,
            String failureCategory) {
        return new ProbeObservation(
                false, Math.max(0L, durationNanos), completedAtEpochMs,
                null, failureCategory == null ? "unknown" : failureCategory);
    }
}
