package cloud.xuantong.common.page;

/** Validated one-based page coordinates shared by repositories and HTTP APIs. */
public record PageQuery(int page, int pageSize) {
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    public PageQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public static PageQuery of(Integer page, Integer pageSize) {
        return new PageQuery(
                page == null ? DEFAULT_PAGE : page,
                pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
    }

    public long offset() {
        return Math.multiplyExact((long) page - 1L, pageSize);
    }
}
