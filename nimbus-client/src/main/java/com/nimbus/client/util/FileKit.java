package com.nimbus.client.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件操作工具类
 */
public class FileKit {
    private static final Logger logger = LoggerFactory.getLogger(FileKit.class);

    public static String readFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return "{}";
            }
            // JDK 1.8兼容写法
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Read file failed: {}", path, e);
            return "{}";
        }
    }

    public static void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            // JDK 1.8兼容写法
            Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("Write file failed: {}", path, e);
            throw new RuntimeException("Write file failed", e);
        }
    }

    public static boolean deleteFile(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.error("Delete file failed: {}", path, e);
            return false;
        }
    }
}