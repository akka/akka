/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern;

import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import java.util.Optional;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

public class RetrySettingsTest extends JUnitSuite {

  @Test
  public void shouldCreateRetrySetting() {
    // mostly for testing compilation issues
    RetrySettings.create(2);
    RetrySettings.create(ConfigFactory.parseString("max-retries = 2"));
    RetrySettings.create(2).withFixedDelay(Duration.ofSeconds(1));
    RetrySettings.create(2)
        .withExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(2), 0.1);
    RetrySettings.create(2).withJavaDelayFunction(retryNum -> Optional.of(Duration.ofSeconds(1)));
    RetrySettings.create(2).withJavaDecider(__ -> true);
  }
}
