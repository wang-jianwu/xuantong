package cloud.xuantong.client.listener;

import cloud.xuantong.client.model.ConfigChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置监听器管理器（支持超时控制、执行策略和监控）
 */
public class ConfigListenerManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigListenerManager.class);
    private static final long DEFAULT_EXECUTION_TIMEOUT = 500; // 500ms
    private static final int MAX_CONCURRENT_LISTENERS = 100;

    // key -> listeners mapping
    private final Map<String, List<ConfigListener>> listenersMap = new ConcurrentHashMap<>();
    // 监听器执行线程池
    private final ExecutorService listenerExecutor;
    // 监控指标
    private final Map<String, Long> listenerExecutionTimes = new ConcurrentHashMap<>();
    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong failedExecutions = new AtomicLong(0);
    private final AtomicLong timeoutExecutions = new AtomicLong(0);

    public ConfigListenerManager() {
        this.listenerExecutor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_LISTENERS,
            r -> {
                Thread thread = new Thread(r, "config-listener-" + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            }
        );
    }

    // 添加监听器
    public void addListener(String key, ConfigListener listener) {
        listenersMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        logger.debug("Added listener for key: {}", key);
    }

    // 移除监听器
    public void removeListener(String key, ConfigListener listener) {
        List<ConfigListener> listeners = listenersMap.get(key);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                listenersMap.remove(key);
            }
        }
    }

    // 触发配置变更事件（支持超时控制和并行执行）
    public void fireEvent(ConfigChangeEvent event) {
        List<ConfigListener> listeners = listenersMap.get(event.getKey());
        if (listeners != null && !listeners.isEmpty()) {
            // 并行执行所有监听器

            // 等待所有监听器完成或超时
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    listeners.stream()
                            .map(listener -> CompletableFuture.runAsync(() ->
                                    executeListenerSafely(listener, event), listenerExecutor)).toArray(CompletableFuture[]::new)
            );

            try {
                allFutures.get(DEFAULT_EXECUTION_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                timeoutExecutions.incrementAndGet();
                logger.warn("Listener execution timeout for key: {}", event.getKey());
            } catch (Exception e) {
                logger.error("Listener execution failed collectively for key: {}", event.getKey(), e);
            }
        }
    }

    // 安全执行单个监听器
    private void executeListenerSafely(ConfigListener listener, ConfigChangeEvent event) {
        long startTime = System.nanoTime();
        totalExecutions.incrementAndGet();
        try {
            logger.info("Executing listener for key: {}", event);
            listener.onConfigChange(event);
            long duration = (System.nanoTime() - startTime) / 1_000_000; // ms
            listenerExecutionTimes.merge(listener.getClass().getSimpleName(), duration, Long::sum);
        } catch (Exception e) {
            failedExecutions.incrementAndGet();
            logger.error("Listener {} execution failed for key: {}",
                       listener.getClass().getSimpleName(), event.getKey(), e);
        }
    }

    // 获取监控指标
    public ListenerMetrics getMetrics() {
        return new ListenerMetrics(
            totalExecutions.get(),
            failedExecutions.get(),
            timeoutExecutions.get(),
            listenersMap.values().stream().mapToInt(List::size).sum(),
            new ConcurrentHashMap<>(listenerExecutionTimes)
        );
    }

    // 优雅关闭
    public void shutdown() {
        listenerExecutor.shutdown();
        try {
            if (!listenerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                listenerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            listenerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // 清空所有监听器
    public void clear() {
        listenersMap.clear();
        listenerExecutionTimes.clear();
        totalExecutions.set(0);
        failedExecutions.set(0);
        timeoutExecutions.set(0);
    }

    // 监控指标类
    public static class ListenerMetrics {
        public final long totalExecutions;
        public final long failedExecutions;
        public final long timeoutExecutions;
        public final int totalListeners;
        public final Map<String, Long> executionTimes;

        public ListenerMetrics(long totalExecutions, long failedExecutions,
                             long timeoutExecutions, int totalListeners,
                             Map<String, Long> executionTimes) {
            this.totalExecutions = totalExecutions;
            this.failedExecutions = failedExecutions;
            this.timeoutExecutions = timeoutExecutions;
            this.totalListeners = totalListeners;
            this.executionTimes = executionTimes;
        }
    }
}