package cloud.xuantong.client.model;

/** Authoritative lease renewal result and the Registry State commit clock. */
public record LeaseRenewalResult(
        ServiceInstance instance,
        long serverTimeEpochMs) {

    public LeaseRenewalResult {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        if (serverTimeEpochMs <= 0L) {
            throw new IllegalArgumentException("serverTimeEpochMs must be positive");
        }
    }
}
