package se.scalablesolutions.akka.api;

import junit.framework.TestCase;
import junit.framework.Test;
import junit.framework.TestSuite;

public class AllTest extends TestCase {
  public static Test suite() {
    TestSuite suite = new TestSuite("All Java tests");
    suite.addTestSuite(InMemoryStateTest.class);
    suite.addTestSuite(InMemNestedStateTest.class);
    suite.addTestSuite(RemoteInMemoryStateTest.class);
    suite.addTestSuite(TypedActorGuiceConfiguratorTest.class);
    return suite;
  }

  public static void main(String[] args) {
    junit.textui.TestRunner.run(suite());
  }
}