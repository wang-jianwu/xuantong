package cloud.xuantong.common.page;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageContractTest {
    @Test
    void validatesPageCoordinates() {
        assertEquals(new PageQuery(1, 20), PageQuery.of(null, null));
        assertEquals(40L, new PageQuery(3, 20).offset());
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 20));
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(1, 201));
    }

    @Test
    void buildsStablePageMetadataAndMapsItems() {
        PageResult<Integer> page = PageResult.of(
                new PageQuery(2, 2), 5, List.of(3, 4));

        assertEquals(3, page.totalPages());
        assertTrue(page.hasPrevious());
        assertTrue(page.hasNext());
        PageResult<String> mapped = page.map(String::valueOf)
                .withMetadata(Map.of("revision", 7L));
        assertEquals(List.of("3", "4"), mapped.items());
        assertEquals(7L, mapped.metadata().get("revision"));
    }

    @Test
    void emptyPageHasNoNavigation() {
        PageResult<String> page = PageResult.of(
                new PageQuery(1, 20), 0, List.of());
        assertEquals(0, page.totalPages());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
    }
}
