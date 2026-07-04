package cloud.xuantong.client;

import cloud.xuantong.client.enums.ValueType;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ConfigListenerManager;
import cloud.xuantong.client.model.ConfigChangeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * xuantong-client JUnit 5 单元测试
 */
public class ClientTestsJUnit {

    // ===== ValueType 推断测试 =====
    @Nested
    @DisplayName("ValueType 推断")
    public static class ValueTypeTests {

        @Test @DisplayName("boolean/Boolean → BOOLEAN")
        void booleanType() {
            assertEquals(ValueType.BOOLEAN, ValueType.inferFromClass(boolean.class));
            assertEquals(ValueType.BOOLEAN, ValueType.inferFromClass(Boolean.class));
        }

        @Test @DisplayName("数值类型 → NUMBER")
        void numberType() {
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(int.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(Integer.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(long.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(Long.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(float.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(Float.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(double.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(Double.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(short.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(Short.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(byte.class));
            assertEquals(ValueType.NUMBER, ValueType.inferFromClass(Byte.class));
        }

        @Test @DisplayName("String → STRING")
        void stringType() {
            assertEquals(ValueType.STRING, ValueType.inferFromClass(String.class));
        }

        @Test @DisplayName("集合/Map/数组/Date → JSON")
        void jsonType() {
            assertEquals(ValueType.JSON, ValueType.inferFromClass(List.class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(ArrayList.class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(Map.class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(HashMap.class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(int[].class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(Object.class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(Date.class));
        }

        @Test @DisplayName("char/Character → JSON（不在 NUMBER 列表）")
        void charType() {
            assertEquals(ValueType.JSON, ValueType.inferFromClass(char.class));
            assertEquals(ValueType.JSON, ValueType.inferFromClass(Character.class));
        }
    }

    // ===== ConfigListenerManager 测试 =====
    @Nested
    @DisplayName("ConfigListenerManager")
    public static class ListenerManagerTests {

        @Test @DisplayName("添加监听器并触发事件")
        void fireEvent() throws Exception {
            ConfigListenerManager mgr = new ConfigListenerManager();
            CountDownLatch latch = new CountDownLatch(1);
            final String[] received = {null};

            mgr.addListener("db.url", event -> {
                received[0] = event.getNewValue();
                latch.countDown();
            });

            mgr.fireEvent(new ConfigChangeEvent("db.url", "jdbc:mysql://new"));
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals("jdbc:mysql://new", received[0]);
            mgr.shutdown();
        }

        @Test @DisplayName("同一 key 多个监听器全部触发")
        void multipleListeners() throws Exception {
            ConfigListenerManager mgr = new ConfigListenerManager();
            int n = 5;
            CountDownLatch latch = new CountDownLatch(n);
            AtomicInteger counter = new AtomicInteger(0);

            for (int i = 0; i < n; i++) {
                mgr.addListener("k", event -> {
                    counter.incrementAndGet();
                    latch.countDown();
                });
            }

            mgr.fireEvent(new ConfigChangeEvent("k", "v"));
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(n, counter.get());
            mgr.shutdown();
        }

        @Test @DisplayName("移除监听器后不再触发")
        void removeListener() throws Exception {
            ConfigListenerManager mgr = new ConfigListenerManager();
            AtomicInteger counter = new AtomicInteger(0);
            ConfigListener listener = event -> counter.incrementAndGet();

            mgr.addListener("k", listener);
            mgr.fireEvent(new ConfigChangeEvent("k", "v1"));
            Thread.sleep(200);
            assertEquals(1, counter.get());

            mgr.removeListener("k", listener);
            mgr.fireEvent(new ConfigChangeEvent("k", "v2"));
            Thread.sleep(200);
            assertEquals(1, counter.get());
            mgr.shutdown();
        }

        @Test @DisplayName("getMetrics 返回正确统计")
        void metrics() {
            ConfigListenerManager mgr = new ConfigListenerManager();
            mgr.addListener("a", e -> {});
            mgr.addListener("a", e -> {});
            mgr.addListener("b", e -> {});

            assertEquals(3, mgr.getMetrics().totalListeners);
            mgr.shutdown();
        }
    }
}
