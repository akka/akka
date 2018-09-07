/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.testkit.typed.scaladsl._
import org.scalatest.Matchers
import org.scalatest.WordSpec

object OrElseSpec {
  sealed trait Ping
  case class Ping1(replyTo: ActorRef[Pong]) extends Ping
  case class Ping2(replyTo: ActorRef[Pong]) extends Ping
  case class Ping3(replyTo: ActorRef[Pong]) extends Ping
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

class OrElseSpec extends WordSpec with Matchers {

  import OrElseSpec._

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

