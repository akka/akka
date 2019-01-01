/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

object FSMDocSpec {

  // messages and data types
  //#test-code

  //#simple-events
  // FSM event becomes the type of the message Actor supports
  sealed trait Event
  final case class SetTarget(ref: ActorRef[Batch]) extends Event
  final case class Queue(obj: Any) extends Event
  case object Flush extends Event
  case object Timeout extends Event
  //#simple-events

  final case class Batch(obj: immutable.Seq[Any])

  //#simple-state
  // states of the FSM represented as behaviors
  def idle(data: Data): Behavior[Event] = Behaviors.receiveMessage[Event] { message: Event ⇒
    (message, data) match {
      case (SetTarget(ref), Uninitialized) ⇒
        idle(Todo(ref, Vector.empty))
      case (Queue(obj), t @ Todo(_, v)) ⇒
        active(t.copy(queue = v :+ obj))
      case _ ⇒
        Behaviors.unhandled
    }
  }

  def active(data: Todo): Behavior[Event] =
    Behaviors.withTimers[Event] { timers ⇒
      // instead of FSM state timeout
      timers.startSingleTimer(Timeout, Timeout, 1.second)
      Behaviors.receiveMessagePartial {
        case Flush | Timeout ⇒
          data.target ! Batch(data.queue)
          idle(data.copy(queue = Vector.empty))
        case Queue(obj) ⇒
          active(data.copy(queue = data.queue :+ obj))
      }
    }
  //#simple-state

  val initialState = idle(Uninitialized)

  //#storing-state
  sealed trait Data
  case object Uninitialized extends Data
  final case class Todo(target: ActorRef[Batch], queue: immutable.Seq[Any]) extends Data
  //#storing-state

  //#test-code
}

class FSMDocSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import FSMDocSpec._

  "FSMDocSpec" must {
    "work" in {
      val buncher = spawn(initialState)
      val probe = TestProbe[Batch]
      buncher ! SetTarget(probe.ref)
      buncher ! Queue(42)
      buncher ! Queue(43)
      probe.expectMessage(Batch(immutable.Seq(42, 43)))
      buncher ! Queue(44)
      buncher ! Flush
      buncher ! Queue(45)
      probe.expectMessage(Batch(immutable.Seq(44)))
      probe.expectMessage(Batch(immutable.Seq(45)))

    }
  }
}
