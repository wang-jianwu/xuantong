package cloud.xuantong.core.config;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库自动初始化 — 启动时执行 schema.sql
 * <p>
 * H2 内嵌模式下无需手动建表；MySQL/PG 也可自动初始化。
 * 使用 CREATE TABLE IF NOT EXISTS + INSERT ... WHERE NOT EXISTS 保证幂等。
 */
@Component
public class DbInitializer {
    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    @Inject
    private DataSource dataSource;

    @Init
    public void init() {
        try {
            List<String> statements = loadStatements();
            if (statements.isEmpty()) {
                log.warn("schema.sql not found, skipping database init");
                return;
            }

            log.info("Initializing database ({} statements)...", statements.size());
            int ok = 0;

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                for (String sql : statements) {
                    try {
                        stmt.execute(sql);
                        ok++;
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg != null && (msg.contains("already exists") || msg.contains("Duplicate") || msg.contains("not found"))) {
                            log.debug("Skipping (already exists or depends on missing table): {}", msg);
                        } else {
                            log.warn("SQL error: {} — {}", msg, sql.substring(0, Math.min(80, sql.length())));
                        }
                    }
                }

            }

            log.info("Database initialized: {} / {} statements executed", ok, statements.size());
        } catch (Exception e) {
            log.error("Database initialization failed", e);
        }
    }

    /** 逐行读取 SQL 文件，按分号拆分为独立语句 */
    private List<String> loadStatements() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("db/schema.sql");
        if (is == null) return new ArrayList<>();

        List<String> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                // 跳过空行和注释
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;

                buf.append(line).append("\n");
                // 遇到分号 → 一条语句结束
                if (trimmed.endsWith(";")) {
                    String stmt = buf.toString().trim();
                    // 去掉末尾分号（JDBC 不需要）
                    if (stmt.endsWith(";")) {
                        stmt = stmt.substring(0, stmt.length() - 1);
                    }
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    buf.setLength(0);
                }
            }
            // 末尾可能有没有分号的语句
            String remaining = buf.toString().trim();
            if (!remaining.isEmpty()) {
                statements.add(remaining);
            }
        }
        return statements;
    }
}
