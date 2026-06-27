package com.bhawana.lms.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagedResultTest {

    @Test
    void exposesItemsAndPaginationMetadata() {
        PagedResult<String> page = new PagedResult<>(List.of("loan-1"), 12, 2, 5);

        assertThat(page.items()).containsExactly("loan-1");
        assertThat(page.totalCount()).isEqualTo(12);
        assertThat(page.offset()).isEqualTo(2);
        assertThat(page.limit()).isEqualTo(5);
    }
}
