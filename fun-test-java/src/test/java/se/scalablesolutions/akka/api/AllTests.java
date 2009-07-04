package se.scalablesolutions.akka.api;

import junit.framework.TestCase;
import junit.framework.Test;
import junit.framework.TestSuite;

public class AllTests extends TestCase {
  public static Test suite() {

    TestSuite suite = new TestSuite("All tests");
    // Java tests
    suite.addTestSuite(InMemoryStateTest.class);
    suite.addTestSuite(InMemNestedStateTest.class);
    suite.addTestSuite(PersistentStateTest.class);
    suite.addTestSuite(PersistentNestedStateTest.class);
    suite.addTestSuite(RemoteInMemoryStateTest.class);
    suite.addTestSuite(RemotePersistentStateTest.class);
    suite.addTestSuite(ActiveObjectGuiceConfiguratorTest.class);
    suite.addTestSuite(RestTest.class);

    // Scala tests
    //suite.addTestSuite(se.scalablesolutions.akka.kernel.SupervisorSpec.class);
      /*
    suite.addTestSuite(se.scalablesolutions.akka.kernel.RemoteSupervisorSpec.class);
    suite.addTestSuite(se.scalablesolutions.akka.kernel.reactor.EventBasedDispatcherTest.class);
    suite.addTestSuite(se.scalablesolutions.akka.kernel.reactor.ThreadBasedDispatcherTest.class);
    suite.addTestSuite(se.scalablesolutions.akka.kernel.actor.ActorSpec.class);
    suite.addTestSuite(se.scalablesolutions.akka.kernel.actor.RemoteActorSpec.class);
    suite.addTestSuite(se.scalablesolutions.akka.kernel.actor.InMemStatefulActor.class);
    suite.addTestSuite(se.scalablesolutions.akka.kernel.actor.PersistentActor.class);
    */
    return suite;
  }

  public static void main(String[] args) {
    junit.textui.TestRunner.run(suite());
  }
}