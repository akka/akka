/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.TestException
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Props
import akka.testkit.EventFilter
import akka.actor.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object MessageAdapterSpec {
  val config = ConfigFactory.parseString("""
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

class MessageAdapterSpec extends ScalaTestWithActorTestKit(MessageAdapterSpec.config) with WordSpecLike {

  implicit val untyped = system.toUntyped // FIXME #24348: eventfilter support in typed testkit

  "Message adapters" must {

    "map messages inside the actor" in {
      case class Ping(sender: ActorRef[Response])
      trait Response
      case class Pong(selfName: String, threadName: String) extends Response

      case class AnotherPong(selfName: String, threadName: String)

      val pingPong = spawn(Behaviors.receive[Ping] { (context, message) =>
        message.sender ! Pong(context.self.path.name, Thread.currentThread().getName)
        Behaviors.same
      }, "ping-pong", Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))

      val probe = TestProbe[AnotherPong]()

      val snitch = Behaviors.setup[AnotherPong] { context =>
        val replyTo =
          context.messageAdapter[Response](_ => AnotherPong(context.self.path.name, Thread.currentThread().getName))
        pingPong ! Ping(replyTo)

        // also verify the internal spawnMessageAdapter
        val replyTo2: ActorRef[Response] =
          context.spawnMessageAdapter(_ => AnotherPong(context.self.path.name, Thread.currentThread().getName))
        pingPong ! Ping(replyTo2)

        Behaviors.receiveMessage { anotherPong =>
          probe.ref ! anotherPong
          Behaviors.same
        }
      }

      spawn(snitch, "snitch", Props.empty.withDispatcherFromConfig("snitch-dispatcher"))

      val response1 = probe.receiveMessage()
      response1.selfName should ===("snitch")
      response1.threadName should startWith("MessageAdapterSpec-snitch-dispatcher")

      // and from the spawnMessageAdapter
      val response2 = probe.receiveMessage()
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

      val pingPong = spawn(Behaviors.receiveMessage[Ping] {
        case Ping1(sender) =>
          sender ! Pong1("hello-1")
          Behaviors.same
        case Ping2(sender) =>
          sender ! Pong2("hello-2")
          Behaviors.same
      })

      val probe = TestProbe[Wrapped]()

      val snitch = Behaviors.setup[Wrapped] { context =>
        context.messageAdapter[Response](pong => Wrapped(qualifier = "wrong", pong)) // this is replaced
        val replyTo1: ActorRef[Response] = context.messageAdapter(pong => Wrapped(qualifier = "1", pong))
        val replyTo2 = context.messageAdapter[Pong2](pong => Wrapped(qualifier = "2", pong))
        pingPong ! Ping1(replyTo1)
        pingPong ! Ping2(replyTo2)

        Behaviors.receiveMessage { wrapped =>
          probe.ref ! wrapped
          Behaviors.same
        }
      }

      spawn(snitch)

      probe.expectMessage(Wrapped("1", Pong1("hello-1")))
      probe.expectMessage(Wrapped("2", Pong2("hello-2")))
    }

    "not break if wrong/unknown response type" in {
      trait Ping
      case class Ping1(sender: ActorRef[Pong1]) extends Ping
      case class Ping2(sender: ActorRef[Pong2]) extends Ping
      trait Response
      case class Pong1(greeting: String) extends Response
      case class Pong2(greeting: String) extends Response

      case class Wrapped(qualifier: String, response: Response)

      val pingPong = spawn(Behaviors.receiveMessage[Ping] {
        case Ping1(sender) =>
          sender ! Pong1("hello-1")
          Behaviors.same
        case Ping2(sender) =>
          // doing something terribly wrong
          sender ! Pong2("hello-2")
          Behaviors.same
      })

      val probe = TestProbe[Wrapped]()

      val snitch = Behaviors.setup[Wrapped] { context =>
        val replyTo1 = context.messageAdapter[Pong1](pong => Wrapped(qualifier = "1", pong))
        pingPong ! Ping1(replyTo1)
        // doing something terribly wrong
        // Pong2 message adapter not registered
        pingPong ! Ping2(replyTo1.asInstanceOf[ActorRef[Pong2]])
        pingPong ! Ping1(replyTo1)

        Behaviors.receiveMessage { wrapped =>
          probe.ref ! wrapped
          Behaviors.same
        }
      }

      EventFilter.warning(start = "unhandled message", occurrences = 1).intercept {
        spawn(snitch)
      }

      probe.expectMessage(Wrapped("1", Pong1("hello-1")))
      // hello-2 discarded because it was wrong type
      probe.expectMessage(Wrapped("1", Pong1("hello-1")))
    }

    "stop when exception from adapter" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(greeting: String)
      case class Wrapped(count: Int, response: Pong)

      val pingPong = spawn(Behaviors.receiveMessage[Ping] { ping =>
        ping.sender ! Pong("hello")
        Behaviors.same
      })

      val probe = TestProbe[Any]()

      val snitch = Behaviors.setup[Wrapped] { context =>
        var count = 0
        val replyTo = context.messageAdapter[Pong] { pong =>
          count += 1
          if (count == 3) throw new TestException("boom")
          else Wrapped(count, pong)
        }
        (1 to 4).foreach { _ =>
          pingPong ! Ping(replyTo)
        }

        Behaviors
          .receiveMessage[Wrapped] { wrapped =>
            probe.ref ! wrapped
            Behaviors.same
          }
          .receiveSignal {
            case (_, PostStop) =>
              probe.ref ! "stopped"
              Behaviors.same
          }
      }

      EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 1).intercept {
        EventFilter[TestException](occurrences = 1).intercept {
          spawn(snitch)
        }
      }

      probe.expectMessage(Wrapped(1, Pong("hello")))
      probe.expectMessage(Wrapped(2, Pong("hello")))
      // exception was thrown for  3

      probe.expectMessage("stopped")
    }

    "not catch exception thrown after adapter, when processing the message" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(greeting: String)
      case class Wrapped(response: Pong)

      val pingPong = spawn(Behaviors.receiveMessage[Ping] { ping =>
        ping.sender ! Pong("hello")
        Behaviors.same
      })

      val probe = TestProbe[Any]()

      val snitch = Behaviors.setup[Wrapped] { context =>
        val replyTo = context.messageAdapter[Pong] { pong =>
          Wrapped(pong)
        }
        (1 to 5).foreach { _ =>
          pingPong ! Ping(replyTo)
        }

        def behv(count: Int): Behavior[Wrapped] =
          Behaviors
            .receiveMessage[Wrapped] { wrapped =>
              probe.ref ! count
              if (count == 3) {
                throw new TestException("boom")
              }
              behv(count + 1)
            }
            .receiveSignal {
              case (_, PostStop) =>
                probe.ref ! "stopped"
                Behaviors.same
            }

        behv(count = 1)
      }

      EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 2).intercept {
        // Not expecting "Exception thrown out of adapter. Stopping myself"
        EventFilter[TestException](message = "boom", occurrences = 1).intercept {
          spawn(snitch)
        }
      }

      probe.expectMessage(1)
      probe.expectMessage(2)
      probe.expectMessage(3)
      // exception was thrown for 3
      probe.expectMessage("stopped")
    }

  }

}
