package cloud.xuantong.client.exception;

public final class DiscoveryLeaseException extends XuantongException {
    public enum Reason {
        EXPIRED,
        FENCED,
        SERVICE_FENCED
    }

    private final Reason reason;

    public DiscoveryLeaseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
