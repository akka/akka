package akka.pattern;

import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import static org.junit.Assert.assertEquals;


public class RetrySettingsTest extends JUnitSuite {

  @Test
  public void shouldCreateRetrySetting() { //mostly for testing compilation issues
    RetrySettings retrySettings = RetrySettings.create(2);
    assertEquals(2, retrySettings.maxRetries());
  }
}
