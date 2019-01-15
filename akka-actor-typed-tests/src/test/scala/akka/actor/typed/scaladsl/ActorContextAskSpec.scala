/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, PostStop, Props }
import akka.testkit.EventFilter
import akka.actor.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

object ActorContextAskSpec {
  val config = ConfigFactory.parseString(
    """
      akka.loggers = ["akka.testkit.TestEventListener"]
      ping-pong-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
      snitch-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
    """)
}

class ActorContextAskSpec extends ScalaTestWithActorTestKit(ActorContextAskSpec.config) with WordSpecLike {

  implicit val untyped = system.toUntyped // FIXME no typed event filter yet

  "The Scala DSL ActorContext" must {

    "provide a safe ask" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(selfName: String, threadName: String)

      val pingPong = spawn(Behaviors.receive[Ping] { (context, message) ⇒
        message.sender ! Pong(context.self.path.name, Thread.currentThread().getName)
        Behaviors.same
      }, "ping-pong", Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))

      val probe = TestProbe[Pong]()

      val snitch = Behaviors.setup[Pong] { context ⇒

        // Timeout comes from TypedAkkaSpec

        context.ask(pingPong)(Ping) {
          case Success(_)  ⇒ Pong(context.self.path.name + "1", Thread.currentThread().getName)
          case Failure(ex) ⇒ throw ex
        }

        Behaviors.receiveMessage { pong ⇒
          probe.ref ! pong
          Behaviors.same
        }
      }

      spawn(snitch, "snitch", Props.empty.withDispatcherFromConfig("snitch-dispatcher"))

      val pong = probe.receiveMessage()

      pong.selfName should ===("snitch1")
      pong.threadName should startWith("ActorContextAskSpec-snitch-dispatcher")
    }

    "fail actor when mapping does not match response" in {
      val probe = TestProbe[AnyRef]()

      trait Protocol
      case class Ping(respondTo: ActorRef[Pong.type]) extends Protocol
      case object Pong extends Protocol

      val pingPong = spawn(Behaviors.receive[Protocol]((_, message) ⇒
        message match {
          case Ping(respondTo) ⇒
            respondTo ! Pong
            Behaviors.same
        }
      ))

      val snitch = Behaviors.setup[AnyRef] { context ⇒
        context.ask(pingPong)(Ping) {
          case Success(message) ⇒ throw new NotImplementedError(message.toString)
          case Failure(x)       ⇒ x
        }

        Behaviors.receive[AnyRef] {
          case (_, message) ⇒
            probe.ref ! message
            Behaviors.same
        }.receiveSignal {

          case (_, PostStop) ⇒
            probe.ref ! "stopped"
            Behaviors.same
        }
      }

      EventFilter[NotImplementedError](occurrences = 1, start = "Pong").intercept {
        spawn(snitch)
      }

      // the exception should cause a failure which should stop the actor
      probe.expectMessage("stopped")
    }

    "deal with timeouts in ask" in {
      val probe = TestProbe[AnyRef]()
      val snitch = Behaviors.setup[AnyRef] { context ⇒

        context.ask[String, String](system.deadLetters)(ref ⇒ "boo") {
          case Success(m) ⇒ m
          case Failure(x) ⇒ x
        }(10.millis, implicitly[ClassTag[String]])

        Behaviors.receiveMessage { message ⇒
          probe.ref ! message
          Behaviors.same
        }
      }

      EventFilter.warning(occurrences = 1, message = "received dead letter without sender: boo").intercept {
        EventFilter.info(occurrences = 1, start = "Message [java.lang.String] without sender").intercept {
          spawn(snitch)
        }
      }

      val exc = probe.expectMessageType[TimeoutException]
      exc.getMessage should include("had already been terminated")
    }

    "must timeout if recipient doesn't reply in time" in {
      val target = spawn(Behaviors.ignore[String])
      val probe = TestProbe[AnyRef]()
      val snitch = Behaviors.setup[AnyRef] { context ⇒

        context.ask[String, String](target)(_ ⇒ "bar") {
          case Success(m) ⇒ m
          case Failure(x) ⇒ x
        }(10.millis, implicitly[ClassTag[String]])

        Behaviors.receiveMessage { message ⇒
          probe.ref ! message
          Behaviors.same
        }
      }

      spawn(snitch)

      val exc = probe.expectMessageType[TimeoutException]
      exc.getMessage should startWith("Ask timed out on")
      exc.getMessage should include(target.path.toString)
      exc.getMessage should include("[java.lang.String]") // message class
      exc.getMessage should include("[10 ms]") // timeout
    }

  }

}
