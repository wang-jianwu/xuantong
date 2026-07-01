package cloud.xuantong.client.util;

import cloud.xuantong.client.exception.XuantongException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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

            // 小文件使用readAllBytes，大文件使用流式读取
            long fileSize = Files.size(path);
            if (fileSize < 10 * 1024 * 1024) { // 小于10MB
                byte[] bytes = Files.readAllBytes(path);
                return new String(bytes, StandardCharsets.UTF_8);
            } else {
                // 大文件使用StringBuilder流式读取
                StringBuilder content = new StringBuilder((int) fileSize);
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[8192];
                    int bytesRead;
                    while ((bytesRead = reader.read(buffer)) != -1) {
                        content.append(buffer, 0, bytesRead);
                    }
                }
                return content.toString();
            }
        } catch (IOException e) {
            logger.error("Read file failed: {}", path, e);
            return "{}";
        }
    }

    /**
     * 流式读取文件内容到字符串（避免OOM）
     */
    public static String readFileStream(Path path) {
        try {
            if (!Files.exists(path)) {
                return "";
            }

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }

            // 移除最后多余的换行符
            if (content.length() > 0) {
                content.setLength(content.length() - 1);
            }

            return content.toString();
        } catch (IOException e) {
            logger.error("Stream read file failed: {}", path, e);
            return "";
        }
    }

    public static void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());

            // 小内容直接写入，大内容使用流式写入
            if (content.length() < 10 * 1024 * 1024) {
                Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                // 大内容使用BufferedWriter流式写入
                try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    writer.write(content);
                }
            }
        } catch (IOException e) {
            logger.error("Write file failed: {}", path, e);
            throw new XuantongException("Write file failed", e);
        }
    }

    /**
     * 流式写入文件内容（避免大字符串内存占用）
     */
    public static void writeFileStream(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(content);
            }
        } catch (IOException e) {
            logger.error("Stream write file failed: {}", path, e);
            throw new XuantongException("Write file failed", e);
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

    /**
     * 流式读取文件内容并处理每一行
     *
     * @param path          文件路径
     * @param lineProcessor 行处理器
     */
    public static void processLines(Path path, LineProcessor lineProcessor) {
        try {
            if (Files.exists(path)) {
                try (java.util.stream.Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                    lines.forEach(lineProcessor::process);
                }
            }
        } catch (IOException e) {
            logger.error("Process lines failed: {}", path, e);
        }
    }

    /**
     * 检查文件是否存在
     */
    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    /**
     * 获取文件大小
     */
    public static long size(Path path) throws IOException {
        return Files.size(path);
    }

    /**
     * 创建目录（包括父目录）
     */
    public static void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    /**
     * 移动/重命名文件
     */
    public static void move(Path source, Path target, StandardCopyOption... options) throws IOException {
        Files.move(source, target, options);
    }

    public interface LineProcessor {
        void process(String line);
    }

    /**
     * 原子写入文件（智能降级策略）
     */
    public static void atomicWrite(Path path, String content) throws IOException {
        // 确保父目录存在
        Path parentDir = path.getParent();
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            writeFile(tempFile, content);

            // 优先使用原子移动（高效）
            try {
                move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 原子移动失败时降级到复制策略（兼容性更好）
                logger.debug("Atomic move failed, fallback to copy strategy: {}", e.getMessage());
                if (Files.exists(tempFile)) {
                    Files.copy(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            deleteFile(tempFile);
        }
    }
    /**
     * 原子写入多行内容（智能降级策略）
     */
    public static void atomicWriteLines(Path path, List<String> lines) throws IOException {
        // 确保父目录存在
        Path parentDir = path.getParent();
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (BufferedWriter writer = createBufferedWriter(tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }

            // 优先使用原子移动（高效）
            try {
                move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 原子移动失败时降级到复制策略（兼容性更好）
                logger.debug("Atomic move failed, fallback to copy strategy: {}", e.getMessage());
                if (Files.exists(tempFile)) {
                    Files.copy(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            deleteFile(tempFile);
        }
    }

    /**
     * 读取所有行内容
     */
    public static List<String> readAllLines(Path path) throws IOException {
        if (exists(path)) {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        }
        return new ArrayList<>();
    }

    /**
     * 创建BufferedWriter（统一异常处理）
     */
    public static BufferedWriter createBufferedWriter(Path path, OpenOption... options) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    /**
     * 创建BufferedReader（统一异常处理）
     */
    public static BufferedReader createBufferedReader(Path path) throws IOException {
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }
}