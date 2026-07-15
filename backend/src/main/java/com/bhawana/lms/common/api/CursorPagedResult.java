package com.bhawana.lms.common.api;

import java.util.List;

public record CursorPagedResult<T>(
        List<T> items,
        String nextCursor,
        int limit
) {
}
