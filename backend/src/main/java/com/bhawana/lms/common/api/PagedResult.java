package com.bhawana.lms.common.api;

import java.util.List;

public record PagedResult<T>(
        List<T> items,
        long totalCount,
        int offset,
        int limit
) {
}
