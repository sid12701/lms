package com.bhawana.lms.security;

public record RateLimitBucketSpec(String bucketKey, int permitsPerMinute) {
}
