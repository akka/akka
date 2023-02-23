/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dispatch

import akka.actor.Actor
import akka.actor.Props
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.DurationInt

object VirtualThreadDispatcherSpec {
  def config = ConfigFactory.parseString("""
      virtual-dispatcher {
          type = Dispatcher
          executor = akka.dispatch.VirtualThreadConfigurator
      }
      """)
}

class VirtualThreadDispatcherSpec extends AkkaSpec(VirtualThreadDispatcherSpec.config) {
  "The Virtual thread dispatcher" should {

    "execute a simple task on a named thread" in {
      val dispatcher = system.dispatchers.lookup("virtual-dispatcher")

      val probe = TestProbe()
      dispatcher.execute(() => probe.ref ! Thread.currentThread().getName)
      (probe.expectMsgType[String] should fullyMatch).regex("""virtual-dispatcher-\d""")
    }
    "execute an actor" in {
      val ref = system.actorOf(Props(new Actor {
        override def receive: Receive = {
          case "give-me-thread" => sender() ! Thread.currentThread().getName
        }
      }).withDispatcher("virtual-dispatcher"))
      val probe = TestProbe()
      ref.tell("give-me-thread", probe.ref)
      (probe.expectMsgType[String] should fullyMatch).regex("""virtual-dispatcher-\d""")
    }

    "not have any problems with blocking" in {
      val refs = (0 to 20).map(_ =>
        system.actorOf(Props(new Actor {
          override def receive: Receive = {
            case "block" =>
              // Now  handled by the JVM virtual thread scheduler rather than actually block
              Thread.sleep(1000)
            case "ping" =>
              sender() ! "pong"
          }
        }).withDispatcher("virtual-dispatcher")))
      val probe = TestProbe()
      refs.init.foreach(_ ! "block")
      refs.last.tell("ping", probe.ref)
      probe.expectMsg(300.millis, "pong")
    }

  }

}
