package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.webhooks")
public class WebhookOutboxProperties {

    private final Redrive redrive = new Redrive();

    public Redrive getRedrive() {
        return redrive;
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
