package se.scalablesolutions.akka

import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite

import se.scalablesolutions.akka.actor.{RemoteActorTest, InMemoryActorTest, ThreadBasedActorTest, SupervisorTest, RemoteSupervisorTest, SchedulerTest}
import se.scalablesolutions.akka.dispatch.{ReactorBasedSingleThreadEventDrivenDispatcherTest, ReactorBasedThreadPoolEventDrivenDispatcherTest}

object AllTest extends TestCase {
  def suite(): Test = {
    val suite = new TestSuite("All Scala tests")
/*    suite.addTestSuite(classOf[SupervisorTest])
    suite.addTestSuite(classOf[RemoteSupervisorTest])
    suite.addTestSuite(classOf[ReactorBasedSingleThreadEventDrivenDispatcherTest])
    suite.addTestSuite(classOf[ReactorBasedThreadPoolEventDrivenDispatcherTest])
    suite.addTestSuite(classOf[ThreadBasedActorTest])
    suite.addTestSuite(classOf[ReactorBasedSingleThreadEventDrivenDispatcherTest])
    suite.addTestSuite(classOf[ReactorBasedThreadPoolEventDrivenDispatcherTest])
    suite.addTestSuite(classOf[RemoteActorTest])
    suite.addTestSuite(classOf[InMemoryActorTest])
    suite.addTestSuite(classOf[SchedulerTest])
    //suite.addTestSuite(classOf[TransactionClasherTest])
*/
    suite
  }

  def main(args: Array[String]) = junit.textui.TestRunner.run(suite)
}