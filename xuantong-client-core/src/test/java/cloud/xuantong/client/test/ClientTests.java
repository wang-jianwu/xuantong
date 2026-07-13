package cloud.xuantong.client.test;

import cloud.xuantong.client.enums.ValueType;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ConfigListenerManager;
import cloud.xuantong.client.model.ConfigChangeEvent;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * xuantong-client-core 单元测试（main 函数，零依赖）
 * 运行：java -ea cloud.xuantong.client.test.ClientTests
 */
public class ClientTests {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("===== xuantong-client-core 单元测试 =====\n");

        // ValueType 推断测试
        testValueType_boolean();
        testValueType_number();
        testValueType_string();
        testValueType_json();
        testValueType_char();

        // ConfigListenerManager 测试
        testListener_fireEvent();
        testListener_multipleListeners();
        testListener_remove();
        testListener_metrics();

        // 汇总
        System.out.println("\n===== 结果 =====");
        System.out.println("通过: " + passed + "  失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ===== ValueType 推断测试 =====

    static void testValueType_boolean() {
        eq("boolean→BOOLEAN",  ValueType.BOOLEAN, ValueType.inferFromClass(boolean.class));
        eq("Boolean→BOOLEAN",  ValueType.BOOLEAN, ValueType.inferFromClass(Boolean.class));
    }

    static void testValueType_number() {
        eq("int→NUMBER",    ValueType.NUMBER, ValueType.inferFromClass(int.class));
        eq("Integer→NUMBER", ValueType.NUMBER, ValueType.inferFromClass(Integer.class));
        eq("long→NUMBER",   ValueType.NUMBER, ValueType.inferFromClass(long.class));
        eq("Long→NUMBER",   ValueType.NUMBER, ValueType.inferFromClass(Long.class));
        eq("float→NUMBER",  ValueType.NUMBER, ValueType.inferFromClass(float.class));
        eq("Float→NUMBER",  ValueType.NUMBER, ValueType.inferFromClass(Float.class));
        eq("double→NUMBER", ValueType.NUMBER, ValueType.inferFromClass(double.class));
        eq("Double→NUMBER", ValueType.NUMBER, ValueType.inferFromClass(Double.class));
        eq("short→NUMBER",  ValueType.NUMBER, ValueType.inferFromClass(short.class));
        eq("Short→NUMBER",  ValueType.NUMBER, ValueType.inferFromClass(Short.class));
        eq("byte→NUMBER",   ValueType.NUMBER, ValueType.inferFromClass(byte.class));
        eq("Byte→NUMBER",   ValueType.NUMBER, ValueType.inferFromClass(Byte.class));
    }

    static void testValueType_string() {
        eq("String→STRING", ValueType.STRING, ValueType.inferFromClass(String.class));
    }

    static void testValueType_json() {
        eq("List→JSON",     ValueType.JSON, ValueType.inferFromClass(List.class));
        eq("ArrayList→JSON", ValueType.JSON, ValueType.inferFromClass(ArrayList.class));
        eq("Map→JSON",      ValueType.JSON, ValueType.inferFromClass(Map.class));
        eq("HashMap→JSON",  ValueType.JSON, ValueType.inferFromClass(HashMap.class));
        eq("int[]→JSON",    ValueType.JSON, ValueType.inferFromClass(int[].class));
        eq("Object→JSON",   ValueType.JSON, ValueType.inferFromClass(Object.class));
        eq("Date→JSON",     ValueType.JSON, ValueType.inferFromClass(Date.class));
    }

    static void testValueType_char() {
        // char 不在 NUMBER 列表中，归为 JSON
        eq("char→JSON",       ValueType.JSON, ValueType.inferFromClass(char.class));
        eq("Character→JSON",  ValueType.JSON, ValueType.inferFromClass(Character.class));
    }

    // ===== ConfigListenerManager 测试 =====

    static void testListener_fireEvent() {
        ConfigListenerManager mgr = new ConfigListenerManager();
        CountDownLatch latch = new CountDownLatch(1);
        final String[] received = {null};

        mgr.addListener("db.url", event -> {
            received[0] = event.getNewValue();
            latch.countDown();
        });

        mgr.fireEvent(new ConfigChangeEvent("public", "DEFAULT_GROUP", "db.url", "jdbc:mysql://new", 1));
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        eq("listener触发并收到新值", "jdbc:mysql://new", received[0]);
        mgr.shutdown();
    }

    static void testListener_multipleListeners() {
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

        mgr.fireEvent(new ConfigChangeEvent("public", "DEFAULT_GROUP", "k", "v", 1));
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        eq("5个监听器全部触发", n, counter.get());
        mgr.shutdown();
    }

    static void testListener_remove() {
        ConfigListenerManager mgr = new ConfigListenerManager();
        AtomicInteger counter = new AtomicInteger(0);
        ConfigListener listener = event -> counter.incrementAndGet();

        mgr.addListener("k", listener);
        mgr.fireEvent(new ConfigChangeEvent("public", "DEFAULT_GROUP", "k", "v1", 1));
        sleep(200);
        eq("移除前触发1次", 1, counter.get());

        mgr.removeListener("k", listener);
        mgr.fireEvent(new ConfigChangeEvent("public", "DEFAULT_GROUP", "k", "v2", 2));
        sleep(200);
        eq("移除后不再触发", 1, counter.get());
        mgr.shutdown();
    }

    static void testListener_metrics() {
        ConfigListenerManager mgr = new ConfigListenerManager();
        mgr.addListener("a", e -> {});
        mgr.addListener("a", e -> {});
        mgr.addListener("b", e -> {});

        eq("totalListeners=3", 3, mgr.getMetrics().totalListeners);
        mgr.shutdown();
    }

    // ===== 辅助方法 =====

    private static <T> void eq(String name, T expected, T actual) {
        if (Objects.equals(expected, actual)) {
            System.out.println("  ✓ " + name);
            passed++;
        } else {
            System.out.println("  ✗ " + name + "  expected=" + expected + " actual=" + actual);
            failed++;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
