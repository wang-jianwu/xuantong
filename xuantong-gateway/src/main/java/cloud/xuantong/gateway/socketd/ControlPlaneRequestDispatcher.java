package cloud.xuantong.gateway.socketd;

import org.noear.solon.annotation.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class ControlPlaneRequestDispatcher {
    private final Map<String, ControlPlaneRequestHandler> handlers = new ConcurrentHashMap<>();

    public void register(ControlPlaneRequestHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        String event = normalizeEvent(handler.event());
        ControlPlaneRequestHandler previous = handlers.putIfAbsent(event, handler);
        if (previous != null && previous != handler) {
            throw new IllegalStateException(
                    "Control-plane event already has a handler: " + event);
        }
    }

    public void unregister(ControlPlaneRequestHandler handler) {
        if (handler == null) {
            return;
        }
        handlers.remove(normalizeEvent(handler.event()), handler);
    }

    ControlPlaneRequestHandler find(String event) {
        return handlers.get(normalizeEvent(event));
    }

    boolean hasHandler(String event) {
        return handlers.containsKey(normalizeEvent(event));
    }

    private static String normalizeEvent(String event) {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("event must not be blank");
        }
        return event.trim();
    }
}
