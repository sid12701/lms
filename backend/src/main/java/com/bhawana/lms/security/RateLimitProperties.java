package com.bhawana.lms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private int authPerMinute = 10;
    private int lspWritePerMinute = 60;

    public int getAuthPerMinute() {
        return authPerMinute;
    }

    public void setAuthPerMinute(int authPerMinute) {
        this.authPerMinute = authPerMinute;
    }

    public int getLspWritePerMinute() {
        return lspWritePerMinute;
    }

    public void setLspWritePerMinute(int lspWritePerMinute) {
        this.lspWritePerMinute = lspWritePerMinute;
    }
}
