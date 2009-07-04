package se.scalablesolutions.akka.kernel

import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite

object AllTests extends TestCase {
  def suite(): Test = {
    val suite = new TestSuite("All tests")
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.SupervisorSpec])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.RemoteSupervisorSpec])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.reactor.EventBasedDispatcherTest])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.reactor.ThreadBasedDispatcherTest])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.actor.ActorSpec])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.actor.RemoteActorSpec])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.actor.PersistentActorSpec])
    suite.addTestSuite(classOf[se.scalablesolutions.akka.kernel.actor.InMemoryActorSpec])
    suite
  }

  def main(args: Array[String]) = junit.textui.TestRunner.run(suite)
}