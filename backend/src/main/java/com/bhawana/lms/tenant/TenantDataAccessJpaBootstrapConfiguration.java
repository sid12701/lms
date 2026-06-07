package com.bhawana.lms.tenant;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Hibernate schema tooling opens JDBC connections before request/worker interceptors run.
 * A short bootstrap scope routes those connections to the admin datasource only.
 */
@Configuration
public class TenantDataAccessJpaBootstrapConfiguration {

    @Bean
    static BeanPostProcessor entityManagerFactoryTenantBootstrapProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof LocalContainerEntityManagerFactoryBean
                        || isFlywayMigrationInitializer(bean)) {
                    TenantDataAccessBootstrap.enter();
                }
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof LocalContainerEntityManagerFactoryBean
                        || isFlywayMigrationInitializer(bean)) {
                    TenantDataAccessBootstrap.exit();
                }
                return bean;
            }

            private static boolean isFlywayMigrationInitializer(Object bean) {
                return bean != null
                        && bean.getClass().getName().endsWith("FlywayMigrationInitializer");
            }
        };
    }
}
