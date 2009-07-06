package se.scalablesolutions.akka.kernel

import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite

import kernel.actor.{ActorSpec, RemoteActorSpec, PersistentActorSpec, InMemoryActorSpec}
import kernel.reactor.{EventBasedSingleThreadDispatcherTest, EventBasedThreadPoolDispatcherTest}

object AllTest extends TestCase {
  def suite(): Test = {
    val suite = new TestSuite("All Scala tests")
    suite.addTestSuite(classOf[SupervisorSpec])
    suite.addTestSuite(classOf[RemoteSupervisorSpec])
    suite.addTestSuite(classOf[EventBasedSingleThreadDispatcherTest])
    suite.addTestSuite(classOf[EventBasedThreadPoolDispatcherTest])
    suite.addTestSuite(classOf[ActorSpec])
    suite.addTestSuite(classOf[RemoteActorSpec])
    suite.addTestSuite(classOf[PersistentActorSpec])
    suite.addTestSuite(classOf[InMemoryActorSpec])
    //suite.addTestSuite(classOf[TransactionClasherSpec])
    suite
  }

  def main(args: Array[String]) = junit.textui.TestRunner.run(suite)
}