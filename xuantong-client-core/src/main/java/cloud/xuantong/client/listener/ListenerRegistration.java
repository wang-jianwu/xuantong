package cloud.xuantong.client.listener;

/**
 * 可关闭的监听注册句柄。
 *
 * <p>调用 {@link #close()} 后，监听器不会再接收新的配置事件。重复关闭是安全的。</p>
 */
@FunctionalInterface
public interface ListenerRegistration extends AutoCloseable {
    @Override
    void close();
}
