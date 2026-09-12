package edgetest;

import com.bhawana.lms.security.ClientIpResolutionFilter;
import com.bhawana.lms.security.EdgeProperties;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Minimal embedded-server application: real {@link ClientIpResolutionFilter} plus the
 * edge probe, with no DB and no security chain.
 *
 * <p>Lives in the {@code edgetest} test package — outside the {@code com.bhawana.lms} scan
 * rooted at {@code LmsApplication} — so these {@code @EnableAutoConfiguration} exclusions
 * can never leak into full-context tests.
 * <p>{@code @SpringBootConfiguration} (without {@code @TestConfiguration}) stops the test
 * bootstrapper from additionally merging the real {@code LmsApplication} (which would drag
 * the full component scan and JPA repositories into this minimal context).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class,
        RabbitAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@EnableConfigurationProperties(EdgeProperties.class)
@Import({ClientIpResolutionFilter.class, EdgeProbeController.class})
public class EdgeEmbeddedApp {
}
