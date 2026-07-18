package cloud.xuantong.server.state.management;

public final class ConfigStateWriteException extends RuntimeException {
    private final boolean commitUnknown;

    ConfigStateWriteException(String message, boolean commitUnknown) {
        super(message);
        this.commitUnknown = commitUnknown;
    }

    ConfigStateWriteException(String message, boolean commitUnknown, Throwable cause) {
        super(message, cause);
        this.commitUnknown = commitUnknown;
    }

    public boolean commitUnknown() {
        return commitUnknown;
    }
}
