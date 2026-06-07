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
}
