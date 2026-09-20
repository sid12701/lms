package com.bhawana.lms.security;

import java.util.ArrayList;
import java.util.List;

public class RateLimitRule {

    private String id;
    private String path;
    private List<String> methods = new ArrayList<>();
    private KeyStrategy key = KeyStrategy.IP;
    private int permitsPerMinute = 10;
    private int permitsSubject = 10;
    private int permitsApplication = 5;
    /**
     * M10 explicit outage policy for this route class when the rate-limit store cannot be
     * reached or a command times out. {@link StoreFailurePolicy#FAIL_CLOSED} is for
     * credential-attack-sensitive endpoints (login/token/refresh/password): they answer
     * bounded 503 + Retry-After instead of allowing unbounded credential attempts while the
     * limiter is blind. Everything else defaults to {@link StoreFailurePolicy#FAIL_OPEN}:
     * those routes are already authenticated and authorized, and a Redis blip must not take
     * the whole API down.
     */
    private StoreFailurePolicy onStoreFailure = StoreFailurePolicy.FAIL_OPEN;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods == null ? new ArrayList<>() : methods;
    }

    public KeyStrategy getKey() {
        return key;
    }

    public void setKey(KeyStrategy key) {
        this.key = key;
    }

    public int getPermitsPerMinute() {
        return permitsPerMinute;
    }

    public void setPermitsPerMinute(int permitsPerMinute) {
        this.permitsPerMinute = permitsPerMinute;
    }

    public int getPermitsSubject() {
        return permitsSubject;
    }

    public void setPermitsSubject(int permitsSubject) {
        this.permitsSubject = permitsSubject;
    }

    public int getPermitsApplication() {
        return permitsApplication;
    }

    public void setPermitsApplication(int permitsApplication) {
        this.permitsApplication = permitsApplication;
    }

    public StoreFailurePolicy getOnStoreFailure() {
        return onStoreFailure;
    }

    public void setOnStoreFailure(StoreFailurePolicy onStoreFailure) {
        this.onStoreFailure = onStoreFailure == null ? StoreFailurePolicy.FAIL_OPEN : onStoreFailure;
    }

    /** What a matching route does when the rate-limit store fails (M10). */
    public enum StoreFailurePolicy {
        /** Reject the request with a bounded 503 + Retry-After — for credential-sensitive routes. */
        FAIL_CLOSED,
        /** Let the request through and count the failure — for authenticated non-credential routes. */
        FAIL_OPEN
    }
}
