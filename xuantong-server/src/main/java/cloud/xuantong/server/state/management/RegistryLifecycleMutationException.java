package cloud.xuantong.server.state.management;

public final class RegistryLifecycleMutationException extends IllegalStateException {
    public enum Reason {
        ACTIVE_LEASES,
        REJECTED
    }

    private final Reason reason;
    private final long serviceGeneration;

    RegistryLifecycleMutationException(
            Reason reason, long serviceGeneration, String message) {
        super(message);
        this.reason = reason;
        this.serviceGeneration = serviceGeneration;
    }

    public Reason reason() {
        return reason;
    }

    public long serviceGeneration() {
        return serviceGeneration;
    }
}
