/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl;

// #junit-integration
import akka.testkit.typed.javadsl.TestKitJunitResource;
import org.junit.ClassRule;
import org.junit.Test;

public class JunitIntegrationExampleTest {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testSomething() {
    TestProbe<String> probe = testKit.createTestProbe();
    // ... assertions etc.
  }

}
// #junit-integration