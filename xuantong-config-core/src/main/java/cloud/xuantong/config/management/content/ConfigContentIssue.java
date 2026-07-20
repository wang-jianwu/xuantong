package cloud.xuantong.config.management.content;

public record ConfigContentIssue(int line, int column, String message) {
    public ConfigContentIssue {
        line = Math.max(1, line);
        column = Math.max(1, column);
        message = message == null || message.isBlank() ? "Invalid config content" : message;
    }
}
