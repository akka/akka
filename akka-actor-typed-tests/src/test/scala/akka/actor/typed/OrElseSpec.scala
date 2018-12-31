/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.testkit.typed.scaladsl._
import org.scalatest.{ Matchers, WordSpec, WordSpecLike }

object OrElseStubbedSpec {

  sealed trait Ping
  final case class Ping1(replyTo: ActorRef[Pong]) extends Ping
  final case class Ping2(replyTo: ActorRef[Pong]) extends Ping
  final case class Ping3(replyTo: ActorRef[Pong]) extends Ping
  final case class PingInfinite(replyTo: ActorRef[Pong]) extends Ping

  case class Pong(counter: Int)

  def ping(counters: Map[String, Int]): Behavior[Ping] = {

    val ping1: Behavior[Ping] = Behaviors.receiveMessagePartial {
      case Ping1(replyTo: ActorRef[Pong]) ⇒
        val newCounters = counters.updated("ping1", counters.getOrElse("ping1", 0) + 1)
        replyTo ! Pong(newCounters("ping1"))
        ping(newCounters)
    }

    val ping2: Behavior[Ping] = Behaviors.receiveMessage {
      case Ping2(replyTo: ActorRef[Pong]) ⇒
        val newCounters = counters.updated("ping2", counters.getOrElse("ping2", 0) + 1)
        replyTo ! Pong(newCounters("ping2"))
        ping(newCounters)
      case _ ⇒ Behaviors.unhandled
    }

    val ping3: Behavior[Ping] = Behaviors.receiveMessagePartial {
      case Ping3(replyTo: ActorRef[Pong]) ⇒
        val newCounters = counters.updated("ping3", counters.getOrElse("ping3", 0) + 1)
        replyTo ! Pong(newCounters("ping3"))
        ping(newCounters)
    }

    ping1.orElse(ping2).orElse(ping3)
  }

}

class OrElseStubbedSpec extends WordSpec with Matchers {

  import OrElseStubbedSpec._

  "Behavior.orElse" must {

    "use first matching behavior" in {
      val inbox = TestInbox[Pong]("reply")
      val testkit = BehaviorTestKit(ping(Map.empty))
      testkit.run(Ping1(inbox.ref))
      inbox.receiveMessage() should ===(Pong(1))
      testkit.run(Ping1(inbox.ref))
      inbox.receiveMessage() should ===(Pong(2))

      testkit.run(Ping2(inbox.ref))
      inbox.receiveMessage() should ===(Pong(1))
      testkit.run(Ping3(inbox.ref))
      inbox.receiveMessage() should ===(Pong(1))
      testkit.run(Ping2(inbox.ref))
      inbox.receiveMessage() should ===(Pong(2))
      testkit.run(Ping3(inbox.ref))
      inbox.receiveMessage() should ===(Pong(2))

      testkit.run(Ping1(inbox.ref))
      inbox.receiveMessage() should ===(Pong(3))
    }

  }

}

class OrElseSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import OrElseStubbedSpec._

  "Behavior.orElse" must {
    "work for deferred behavior on the left" in {
      val orElseDeferred = Behaviors.setup[Ping] { _ ⇒
        Behaviors.receiveMessage { _ ⇒
          Behaviors.unhandled
        }
      }.orElse(ping(Map.empty))

      val p = spawn(orElseDeferred)
      val probe = TestProbe[Pong]
      p ! Ping1(probe.ref)
      probe.expectMessage(Pong(1))

    }

    "work for deferred behavior on the right" in {
      val orElseDeferred = ping(Map.empty).orElse(Behaviors.setup { _ ⇒
        Behaviors.receiveMessage {
          case PingInfinite(replyTo) ⇒
            replyTo ! Pong(-1)
            Behaviors.same
        }
      })

      val p = spawn(orElseDeferred)
      val probe = TestProbe[Pong]
      p ! PingInfinite(probe.ref)
      probe.expectMessage(Pong(-1))
    }
  }

  "handle nested OrElse" in {

    sealed trait Parent
    final case class Add(o: Any) extends Parent
    final case class Remove(o: Any) extends Parent
    final case class Stack(s: ActorRef[Array[StackTraceElement]]) extends Parent
    final case class Get(s: ActorRef[Set[Any]]) extends Parent

    def dealer(set: Set[Any]): Behavior[Parent] = {
      val add = Behaviors.receiveMessage[Parent] {
        case Add(o) ⇒ dealer(set + o)
        case _      ⇒ Behaviors.unhandled
      }
      val remove = Behaviors.receiveMessage[Parent] {
        case Remove(o) ⇒ dealer(set - o)
        case _         ⇒ Behaviors.unhandled
      }
      val getStack = Behaviors.receiveMessagePartial[Parent] {
        case Stack(sender) ⇒
          sender ! Thread.currentThread().getStackTrace
          Behaviors.same
      }
      val getSet = Behaviors.receiveMessagePartial[Parent] {
        case Get(sender) ⇒
          sender ! set
          Behaviors.same
      }
      add.orElse(remove).orElse(getStack).orElse(getSet)
    }

    val y = spawn(dealer(Set.empty))

    (0 to 10000) foreach { i ⇒
      y ! Add(i)
    }
    (0 to 9999) foreach { i ⇒
      y ! Remove(i)
    }
    val probe = TestProbe[Set[Any]]
    y ! Get(probe.ref)
    probe.expectMessage(Set[Any](10000))

  }

}

