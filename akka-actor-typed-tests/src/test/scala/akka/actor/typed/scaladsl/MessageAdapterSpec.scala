/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorRef
import akka.actor.typed.PostStop
import akka.actor.typed.Props
import akka.actor.typed.TestException
import akka.actor.typed.TypedAkkaSpecWithShutdown
import akka.testkit.EventFilter
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory

object MessageAdapterSpec {
  val config = ConfigFactory.parseString(
    """
      akka.loggers = ["akka.testkit.TestEventListener"]
      akka.log-dead-letters = off
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

class MessageAdapterSpec extends TestKit(MessageAdapterSpec.config) with TypedAkkaSpecWithShutdown {

  implicit val untyped = system.toUntyped // FIXME no typed event filter yet

  "Message adapters" must {

    "map messages inside the actor" in {
      case class Ping(sender: ActorRef[Response])
      trait Response
      case class Pong(selfName: String, threadName: String) extends Response

      case class AnotherPong(selfName: String, threadName: String)

      val pingPong = spawn(Behaviors.immutable[Ping] { (ctx, msg) ⇒
        msg.sender ! Pong(ctx.self.path.name, Thread.currentThread().getName)
        Behaviors.same
      }, "ping-pong", Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))

      val probe = TestProbe[AnotherPong]()

      val snitch = Behaviors.deferred[AnotherPong] { (ctx) ⇒

        val replyTo = ctx.messageAdapter[Response](_ ⇒
          AnotherPong(ctx.self.path.name, Thread.currentThread().getName))
        pingPong ! Ping(replyTo)

        // also verify the internal spawnMessageAdapter
        val replyTo2: ActorRef[Response] = ctx.spawnMessageAdapter(_ ⇒
          AnotherPong(ctx.self.path.name, Thread.currentThread().getName))
        pingPong ! Ping(replyTo2)

        Behaviors.immutable {
          case (_, anotherPong: AnotherPong) ⇒
            probe.ref ! anotherPong
            Behaviors.same
        }
      }

      spawn(snitch, "snitch", Props.empty.withDispatcherFromConfig("snitch-dispatcher"))

      val response1 = probe.expectMsgType[AnotherPong]
      response1.selfName should ===("snitch")
      response1.threadName should startWith("MessageAdapterSpec-snitch-dispatcher")

      // and from the spawnMessageAdapter
      val response2 = probe.expectMsgType[AnotherPong]
      response2.selfName should ===("snitch")
      response2.threadName should startWith("MessageAdapterSpec-snitch-dispatcher")
    }

    "use the right adapter" in {
      trait Ping
      case class Ping1(sender: ActorRef[Pong1]) extends Ping
      case class Ping2(sender: ActorRef[Pong2]) extends Ping
      trait Response
      case class Pong1(greeting: String) extends Response
      case class Pong2(greeting: String) extends Response

      case class Wrapped(qualifier: String, response: Response)

      val pingPong = spawn(Behaviors.immutable[Ping] { (_, msg) ⇒
        msg match {
          case Ping1(sender) ⇒
            sender ! Pong1("hello-1")
            Behaviors.same
          case Ping2(sender) ⇒
            sender ! Pong2("hello-2")
            Behaviors.same
        }
      })

      val probe = TestProbe[Wrapped]()

      val snitch = Behaviors.deferred[Wrapped] { (ctx) ⇒

        ctx.messageAdapter[Response](pong ⇒ Wrapped(qualifier = "wrong", pong)) // this is replaced
        val replyTo1: ActorRef[Response] = ctx.messageAdapter(pong ⇒ Wrapped(qualifier = "1", pong))
        val replyTo2 = ctx.messageAdapter[Pong2](pong ⇒ Wrapped(qualifier = "2", pong))
        pingPong ! Ping1(replyTo1)
        pingPong ! Ping2(replyTo2)

        Behaviors.immutable {
          case (_, wrapped) ⇒
            probe.ref ! wrapped
            Behaviors.same
        }
      }

      spawn(snitch)

      probe.expectMsg(Wrapped("1", Pong1("hello-1")))
      probe.expectMsg(Wrapped("2", Pong2("hello-2")))
    }

    "not break if wrong/unknown response type" in {
      trait Ping
      case class Ping1(sender: ActorRef[Pong1]) extends Ping
      case class Ping2(sender: ActorRef[Pong2]) extends Ping
      trait Response
      case class Pong1(greeting: String) extends Response
      case class Pong2(greeting: String) extends Response

      case class Wrapped(qualifier: String, response: Response)

      val pingPong = spawn(Behaviors.immutable[Ping] { (_, msg) ⇒
        msg match {
          case Ping1(sender) ⇒
            sender ! Pong1("hello-1")
            Behaviors.same
          case Ping2(sender) ⇒
            // doing something terribly wrong
            sender ! Pong2("hello-2")
            Behaviors.same
        }
      })

      val probe = TestProbe[Wrapped]()

      val snitch = Behaviors.deferred[Wrapped] { (ctx) ⇒

        val replyTo1 = ctx.messageAdapter[Pong1](pong ⇒ Wrapped(qualifier = "1", pong))
        pingPong ! Ping1(replyTo1)
        // doing something terribly wrong
        // Pong2 message adapter not registered
        pingPong ! Ping2(replyTo1.asInstanceOf[ActorRef[Pong2]])
        pingPong ! Ping1(replyTo1)

        Behaviors.immutable {
          case (_, wrapped) ⇒
            probe.ref ! wrapped
            Behaviors.same
        }
      }

      EventFilter.warning(start = "unhandled message", occurrences = 1).intercept {
        spawn(snitch)
      }

      probe.expectMsg(Wrapped("1", Pong1("hello-1")))
      // hello-2 discarded because it was wrong type
      probe.expectMsg(Wrapped("1", Pong1("hello-1")))
    }

    "stop when exception from adapter" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(greeting: String)
      case class Wrapped(count: Int, response: Pong)

      val pingPong = spawn(Behaviors.immutable[Ping] { (_, ping) ⇒
        ping.sender ! Pong("hello")
        Behaviors.same
      })

      val probe = TestProbe[Any]()

      val snitch = Behaviors.deferred[Wrapped] { (ctx) ⇒

        var count = 0
        val replyTo = ctx.messageAdapter[Pong] { pong ⇒
          count += 1
          if (count == 3) throw new TestException("boom")
          else Wrapped(count, pong)
        }
        (1 to 4).foreach { _ ⇒
          pingPong ! Ping(replyTo)
        }

        Behaviors.immutable[Wrapped] {
          case (_, wrapped) ⇒
            probe.ref ! wrapped
            Behaviors.same
        }.onSignal {
          case (_, PostStop) ⇒
            probe.ref ! "stopped"
            Behaviors.same
        }
      }

      EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 1).intercept {
        EventFilter[TestException](occurrences = 1).intercept {
          spawn(snitch)
        }
      }

      probe.expectMsg(Wrapped(1, Pong("hello")))
      probe.expectMsg(Wrapped(2, Pong("hello")))
      // exception was thrown for  3

      // FIXME One thing to be aware of is that the supervision strategy of the Behavior is not
      // used for exceptions from adapters. Should we instead catch, log, unhandled, and resume?
      // It's kind of "before" the message arrives.
      probe.expectMsg("stopped")
    }

  }

}
