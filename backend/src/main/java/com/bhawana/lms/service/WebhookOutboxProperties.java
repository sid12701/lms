package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.webhooks")
public class WebhookOutboxProperties {

    private final Redrive redrive = new Redrive();
    private final Delivery delivery = new Delivery();

    public Redrive getRedrive() {
        return redrive;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public static class Delivery {

        private int maxAttempts = 8;

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
