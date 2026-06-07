package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.webhooks")
public class WebhookOutboxProperties {

    private final SoftFourxx softFourxx = new SoftFourxx();
    private final Redrive redrive = new Redrive();

    public SoftFourxx getSoftFourxx() {
        return softFourxx;
    }

    public Redrive getRedrive() {
        return redrive;
    }

    public static class SoftFourxx {

        private int maxAttempts = 10;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Redrive {

        private int maxManualRedrives = 3;

        public int getMaxManualRedrives() {
            return maxManualRedrives;
        }

        public void setMaxManualRedrives(int maxManualRedrives) {
            this.maxManualRedrives = maxManualRedrives;
        }
    }
}
