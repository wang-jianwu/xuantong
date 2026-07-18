package cloud.xuantong.raft.ratis;

import java.io.IOException;

public class RatisOperationException extends IOException {
    public RatisOperationException(String message) {
        super(message);
    }

    public RatisOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
