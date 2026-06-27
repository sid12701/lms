package com.bhawana.lms.common.api;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class PaginationResponseBuilder {

    public static final String TOTAL_COUNT_HEADER = "X-Total-Count";
    public static final String LIMIT_HEADER = "X-Limit";
    public static final String OFFSET_HEADER = "X-Offset";
    public static final int DEFAULT_LIMIT = 50;

    private PaginationResponseBuilder() {
    }

    public static boolean includePaginationDetails(String paginationDetails) {
        if (paginationDetails == null || paginationDetails.trim().isBlank()) {
            return false;
        }

        String normalized = paginationDetails.trim().toUpperCase(Locale.ROOT);
        if ("ON".equals(normalized)) {
            return true;
        }
        if ("OFF".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("paginationDetails must be either ON or OFF.");
    }

    public static boolean isPaginationRequested(Integer offset, Integer limit, boolean includePaginationDetails) {
        return includePaginationDetails || offset != null || limit != null;
    }

    public static int resolveOffset(Integer offset, boolean paginationRequested) {
        if (!paginationRequested) {
            return 0;
        }
        return offset == null ? 0 : offset;
    }

    public static int resolveLimit(Integer limit, boolean paginationRequested) {
        if (!paginationRequested) {
            return Integer.MAX_VALUE;
        }
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    public static <T> ResponseEntity<List<T>> toListResponse(
            PagedResult<T> page,
            boolean includePaginationDetails
    ) {
        HttpHeaders headers = new HttpHeaders();
        if (includePaginationDetails) {
            headers.add(TOTAL_COUNT_HEADER, Long.toString(page.totalCount()));
            headers.add(LIMIT_HEADER, Integer.toString(page.limit()));
            headers.add(OFFSET_HEADER, Integer.toString(page.offset()));
        }
        return new ResponseEntity<>(page.items(), headers, HttpStatus.OK);
    }
}
