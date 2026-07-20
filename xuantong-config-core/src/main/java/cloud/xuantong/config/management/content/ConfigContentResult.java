package cloud.xuantong.config.management.content;

import java.util.List;

public record ConfigContentResult(
        boolean valid,
        String contentType,
        String content,
        int contentBytes,
        List<ConfigContentIssue> issues) {

    public ConfigContentResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    static ConfigContentResult valid(ConfigContentType type, String content, int contentBytes) {
        return new ConfigContentResult(true, type.wireName(), content, contentBytes, List.of());
    }

    static ConfigContentResult invalid(
            ConfigContentType type, String content, int contentBytes, ConfigContentIssue issue) {
        return new ConfigContentResult(
                false, type.wireName(), content, contentBytes, List.of(issue));
    }
}
