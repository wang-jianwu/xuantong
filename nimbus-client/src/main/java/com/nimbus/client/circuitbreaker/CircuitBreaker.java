package com.nimbus.client.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器实现 (基于滑动窗口，支持动态配置和监控指标)
 */
public class CircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,   // 正常状态
        OPEN,     // 熔断状态
        HALF_OPEN // 半开状态（尝试恢复）
    }

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastStateChangeTime = new AtomicLong(System.currentTimeMillis());

    private volatile int failureThreshold;  // 失败阈值（支持动态调整）
    private volatile long resetTimeout;     // 重置超时(毫秒)（支持动态调整）
    private final int halfOpenSuccessThreshold; // 半开成功阈值
    private final int maxSuccessCount;      // 最大成功计数限制

    // 监控指标
    private long totalRequests = 0;
    private long totalFailures = 0;
    private long totalSuccesses = 0;

    public CircuitBreaker(int failureThreshold, long resetTimeout, int halfOpenSuccessThreshold) {
        this(failureThreshold, resetTimeout, halfOpenSuccessThreshold, 1000);
    }

    public CircuitBreaker(int failureThreshold, long resetTimeout,
                         int halfOpenSuccessThreshold, int maxSuccessCount) {
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
        this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
        this.maxSuccessCount = maxSuccessCount;
    }

    public boolean allowRequest() {
        totalRequests++;
        if (state == State.OPEN) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFailureTime.get() > resetTimeout) {
                transitionToState(State.HALF_OPEN, "Reset timeout reached");
                failureCount.set(0);
                successCount.set(0);
                return true;
            }
            return false;
        }
        return true;
    }

    public void recordSuccess() {
        totalSuccesses++;
        if (state == State.HALF_OPEN) {
            int currentSuccess = successCount.incrementAndGet();
            if (currentSuccess >= halfOpenSuccessThreshold) {
                transitionToState(State.CLOSED, "Half-open success threshold reached");
                failureCount.set(0);
                successCount.set(0);
            }
            // 限制成功计数，防止无限增长
            if (successCount.get() > maxSuccessCount) {
                successCount.set(maxSuccessCount);
            }
        }
    }

    public void recordFailure() {
        totalFailures++;
        long currentTime = System.currentTimeMillis();
        lastFailureTime.set(currentTime);

        if (state == State.HALF_OPEN) {
            transitionToState(State.OPEN, "Failure in half-open state");
            return;
        }

        int count = failureCount.incrementAndGet();
        if (count >= failureThreshold && state == State.CLOSED) {
            transitionToState(State.OPEN, "Failure threshold reached: " + count);
        }
    }

    // 状态转换辅助方法
    private void transitionToState(State newState, String reason) {
        State oldState = this.state;
        this.state = newState;
        lastStateChangeTime.set(System.currentTimeMillis());
        logger.info("Circuit breaker state changed: {} -> {} ({})",
                   oldState, newState, reason);
    }

    public State getState() {
        return state;
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    public long getStateDuration() {
        return System.currentTimeMillis() - lastStateChangeTime.get();
    }

    // 动态配置调整
    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
        logger.info("Circuit breaker failure threshold set to {}", failureThreshold);
    }

    public void setResetTimeout(long resetTimeout) {
        this.resetTimeout = resetTimeout;
        logger.info("Circuit breaker reset timeout set to {}ms", resetTimeout);
    }

    // 获取监控指标
    public CircuitBreakerMetrics getMetrics() {
        return new CircuitBreakerMetrics(
            totalRequests,
            totalSuccesses,
            totalFailures,
            getStateDuration(),
            state,
            failureCount.get(),
            successCount.get()
        );
    }

    public void reset() {
        transitionToState(State.CLOSED, "Manual reset");
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(0);
        totalRequests = 0;
        totalSuccesses = 0;
        totalFailures = 0;
    }

    // 监控指标类
    public static class CircuitBreakerMetrics {
        public final long totalRequests;
        public final long totalSuccesses;
        public final long totalFailures;
        public final long stateDuration;
        public final State currentState;
        public final int currentFailureCount;
        public final int currentSuccessCount;

        public CircuitBreakerMetrics(long totalRequests, long totalSuccesses, long totalFailures,
                                   long stateDuration, State currentState,
                                   int currentFailureCount, int currentSuccessCount) {
            this.totalRequests = totalRequests;
            this.totalSuccesses = totalSuccesses;
            this.totalFailures = totalFailures;
            this.stateDuration = stateDuration;
            this.currentState = currentState;
            this.currentFailureCount = currentFailureCount;
            this.currentSuccessCount = currentSuccessCount;
        }
    }
}