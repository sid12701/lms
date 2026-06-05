package com.bhawana.lms.domain;

public enum AuthEventType {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    API_CLIENT_TOKEN_SUCCEEDED,
    API_CLIENT_TOKEN_FAILED,
    TOKEN_REFRESH_SUCCEEDED,
    TOKEN_REFRESH_FAILED,
    LOGOUT,
    PASSWORD_CHANGED
}
