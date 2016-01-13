/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.actor.ActorSystemImpl
import org.testng.annotations.AfterClass
import akka.stream.testkit.AkkaSpec
import akka.event.Logging
import akka.testkit.TestEvent
import akka.testkit.EventFilter
import org.testng.annotations.BeforeClass

trait ActorSystemLifecycle {

  protected var _system: ActorSystem = _

  final def system: ActorSystem = _system

  def shutdownTimeout: FiniteDuration = 10.seconds

  @BeforeClass
  def createActorSystem(): Unit = {
    _system = ActorSystem(Logging.simpleName(getClass), AkkaSpec.testConf)
    _system.eventStream.publish(TestEvent.Mute(EventFilter[RuntimeException]("Test exception")))
  }

  @AfterClass
  def shutdownActorSystem(): Unit = {
    try {
      system.shutdown()
      system.awaitTermination(shutdownTimeout)
    } catch {
      case _: TimeoutException ⇒
        val msg = "Failed to stop [%s] within [%s] \n%s".format(system.name, shutdownTimeout,
          system.asInstanceOf[ActorSystemImpl].printTree)
        throw new RuntimeException(msg)
    }
  }

}
