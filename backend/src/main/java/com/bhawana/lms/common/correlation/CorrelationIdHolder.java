package com.bhawana.lms.common.correlation;

public final class CorrelationIdHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationIdHolder() {
    }

    public static void set(String correlationId) {
        CURRENT.set(correlationId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
