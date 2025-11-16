package com.nimbus.client.cache;

import com.nimbus.client.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 本地文件缓存实现 (优化版，使用key=value格式存储)
 */
public class LocalFileCache implements ConfigCache {
    private static final Logger logger = LoggerFactory.getLogger(LocalFileCache.class);
    private static final String CACHE_DIR = ".nimbus-cache";
    private static final String CACHE_FILE = "config.properties";

    private final Path cachePath;
    private final ReentrantLock lock = new ReentrantLock();

    public LocalFileCache() {
        this.cachePath = Paths.get(System.getProperty("user.dir"), CACHE_DIR, CACHE_FILE);
        ensureCacheFile();
    }

    private void ensureCacheFile() {
        File file = cachePath.toFile();
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            FileUtil.writeFile(cachePath, "");
        }
    }

    public String get(String key) {
        lock.lock();
        try {
            Map<String, String> cacheMap = loadCache();
            return cacheMap.get(key);
        } finally {
            lock.unlock();
        }
    }

    public void put(String key, String value) {
        lock.lock();
        try {
            Map<String, String> cacheMap = loadCache();
            cacheMap.put(key, value);
            saveCache(cacheMap);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            FileUtil.writeFile(cachePath, "");
        } finally {
            lock.unlock();
        }
    }

    public void remove(String key) {
        lock.lock();
        try {
            Map<String, String> cacheMap = loadCache();
            cacheMap.remove(key);
            saveCache(cacheMap);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, String> getAll() {
        lock.lock();
        try {
            return loadCache();
        } finally {
            lock.unlock();
        }
    }

    private Map<String, String> loadCache() {
        try {
            String content = FileUtil.readFile(cachePath);
            if (content == null || content.trim().isEmpty()) {
                return new HashMap<>();
            }

            return Arrays.stream(content.split("\n"))
                    .filter(line -> line.contains("="))
                    .collect(Collectors.toMap(
                            line -> line.substring(0, line.indexOf('=')),
                            line -> line.substring(line.indexOf('=') + 1)
                    ));
        } catch (Exception e) {
            logger.error("Load cache failed", e);
            return new HashMap<>();
        }
    }

    private void saveCache(Map<String, String> cacheMap) {
        try {
            // 使用StringBuilder替代stream拼接，提升大map时的性能
            StringBuilder content = new StringBuilder(cacheMap.size() * 50); // 预估每行约50字符
            for (Map.Entry<String, String> entry : cacheMap.entrySet()) {
                content.append(entry.getKey())
                      .append('=')
                      .append(entry.getValue())
                      .append('\n');
            }
            // 删除最后一个多余的换行符
            if (content.length() > 0) {
                content.setLength(content.length() - 1);
            }

            FileUtil.writeFile(cachePath, content.toString());
        } catch (Exception e) {
            logger.error("Save cache failed", e);
        }
    }
}