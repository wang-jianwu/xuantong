package cloud.xuantong.common.page;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Stable page response contract for all Xuantong management list APIs. */
public record PageResult<T>(
        List<T> items,
        int page,
        int pageSize,
        long totalItems,
        long totalPages,
        boolean hasPrevious,
        boolean hasNext,
        Map<String, Object> metadata) {

    public PageResult {
        items = List.copyOf(items == null ? List.of() : items);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public static <T> PageResult<T> of(
            PageQuery query, long totalItems, List<T> items) {
        long safeTotal = Math.max(0L, totalItems);
        long totalPages = safeTotal == 0L
                ? 0L
                : (safeTotal + query.pageSize() - 1L) / query.pageSize();
        return new PageResult<>(
                items,
                query.page(),
                query.pageSize(),
                safeTotal,
                totalPages,
                query.page() > 1,
                query.page() < totalPages,
                Map.of());
    }

    public PageResult<T> withMetadata(Map<String, Object> metadata) {
        return new PageResult<>(items, page, pageSize, totalItems, totalPages,
                hasPrevious, hasNext, metadata);
    }

    public <R> PageResult<R> map(Function<? super T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(),
                page, pageSize, totalItems, totalPages, hasPrevious, hasNext, metadata);
    }
}
