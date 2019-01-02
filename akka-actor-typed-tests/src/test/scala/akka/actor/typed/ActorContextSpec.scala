/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.InvalidMessageException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.testkit.typed.scaladsl.TestProbe

import scala.concurrent.duration._
import scala.reflect.ClassTag
import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.testkit.EventFilter
import org.scalatest.WordSpecLike

object ActorSpecMessages {

  sealed trait Command

  sealed trait Event

  case object Ping extends Command

  object Pong extends Event

  case class Renew(replyTo: ActorRef[Renewed.type]) extends Command

  case object Renewed extends Event

  case object Miss extends Command

  case object Missed extends Event

  case object Fail extends Command

  case object Stop extends Command

  case class StopRef[T](ref: ActorRef[T]) extends Command

  case class GotSignal(signal: Signal) extends Event

  case class GotChildSignal(signal: Signal) extends Event

  case class ChildMade(ref: ActorRef[Command]) extends Event

  case object Inert extends Command

  case object InertEvent extends Event

  case class Watch(ref: ActorRef[Command]) extends Command

  case class UnWatch(ref: ActorRef[Command]) extends Command

  case object TimeoutSet extends Event

  case object ReceiveTimeout extends Command

  case class SetTimeout(duration: FiniteDuration) extends Command

  case object GotReceiveTimeout extends Event

}

abstract class ActorContextSpec extends ScalaTestWithActorTestKit(
  """
    akka.loggers = [akka.testkit.TestEventListener]
  """) with WordSpecLike {

  // FIXME eventfilter support in typed testkit
  import scaladsl.adapter._
  implicit val untypedSystem = system.toUntyped

  import ActorSpecMessages._

  def decoration[T]: Behavior[T] ⇒ Behavior[T]

  implicit class BehaviorDecorator[T](behavior: Behavior[T])(implicit ev: ClassTag[T]) {
    def decorate: Behavior[T] = decoration[T](behavior)
  }

  "An ActorContext" must {

    "be usable from Behavior.interpretMessage" in {
      // compilation only
      lazy val b: Behavior[String] = Behaviors.receive { (context, message) ⇒
        Behavior.interpretMessage(b, context, message)
      }
    }

    "canonicalize behaviors" in {
      val probe = TestProbe[Event]()

      lazy val behavior: Behavior[Command] = Behaviors.receive[Command] { (_, message) ⇒
        message match {
          case Ping ⇒
            probe.ref ! Pong
            Behaviors.same
          case Miss ⇒
            probe.ref ! Missed
            Behaviors.unhandled
          case Renew(ref) ⇒
            ref ! Renewed
            behavior
          case other ⇒
            throw new RuntimeException(s"Unexpected message: $other")
        }
      }.decorate

      val actor = spawn(behavior)
      actor ! Ping
      probe.expectMessage(Pong)
      // unhandled gives warning from EventFilter
      EventFilter.warning(occurrences = 1).intercept {
        actor ! Miss
        probe.expectMessage(Missed)
      }
      actor ! Renew(probe.ref)
      probe.expectMessage(Renewed)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "correctly wire the lifecycle hook" in {
      val probe = TestProbe[Event]()

      val internal = (Behaviors.receivePartial[Command] {
        case (_, Fail) ⇒
          throw new TestException("Boom")
      } receiveSignal {
        case (_, signal) ⇒
          probe.ref ! GotSignal(signal)
          Behaviors.same
      }).decorate

      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.restart)
      val actor = spawn(behavior)
      EventFilter[TestException](occurrences = 1).intercept {
        actor ! Fail
      }
      probe.expectMessage(GotSignal(PreRestart))
    }

    "signal post stop after voluntary termination" in {
      val probe = TestProbe[Event]()

      val behavior: Behavior[Command] = (
        Behaviors.receivePartial[Command] {
          case (_, Stop) ⇒ Behaviors.stopped
        } receiveSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }).decorate

      val actor = spawn(behavior)
      actor ! Stop
      probe.expectMessage(GotSignal(PostStop))
    }

    "restart and stop a child actor" in {
      val probe = TestProbe[Event]()

      val child: Behavior[Command] = (Behaviors.receivePartial[Command] {
        case (_, Fail) ⇒ throw new TestException("Boom")
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
      } receiveSignal {
        case (_, signal) ⇒
          probe.ref ! GotChildSignal(signal)
          Behavior.stopped
      }).decorate

      val parent: Behavior[Command] = Behaviors.setup[Command](context ⇒ {
        val childRef = context.spawnAnonymous(
          Behaviors.supervise(child).onFailure(SupervisorStrategy.restart)
        )
        context.watch(childRef)
        probe.ref ! ChildMade(childRef)

        (Behaviors.receivePartial[Command] {
          case (context, StopRef(ref)) ⇒
            context.stop(ref)
            Behavior.same
        } receiveSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behavior.stopped
        }).decorate
      })

      val parentRef = spawn(parent)
      val childRef = probe.expectMessageType[ChildMade].ref
      EventFilter[TestException](occurrences = 1).intercept {
        childRef ! Fail
      }
      probe.expectMessage(GotChildSignal(PreRestart))
      childRef ! Ping
      probe.expectMessage(Pong)
      parentRef ! StopRef(childRef)
      probe.expectTerminated(childRef, timeout.duration)
    }

    "stop a child actor" in {
      val probe = TestProbe[Event]()

      val child: Behavior[Command] = Behaviors.empty[Command].decorate
      val parent: Behavior[Command] = Behaviors.setup[Command](context ⇒ {
        val childRef = context.spawnAnonymous(child)
        context.watch(childRef)
        probe.ref ! ChildMade(childRef)
        Behaviors.receivePartial[Command] {
          case (context, StopRef(ref)) ⇒
            context.stop(ref)
            Behaviors.same
        } receiveSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behavior.stopped
        }
      }).decorate
      val parentRef = spawn(parent)
      val childRef = probe.expectMessageType[ChildMade].ref
      parentRef ! StopRef(childRef)
      probe.expectTerminated(childRef, timeout.duration)
    }

    "reset behavior upon restart" in {
      val probe = TestProbe[Int]()
      val internal = Behaviors.setup[Command](_ ⇒ {
        var counter = 0
        Behaviors.receivePartial[Command] {
          case (_, Ping) ⇒
            counter += 1
            probe.ref ! counter
            Behavior.same
          case (_, Fail) ⇒
            throw new TestException("Boom")
        }
      }).decorate
      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.restart)
      val actor = spawn(behavior)
      actor ! Ping
      probe.expectMessage(1)
      EventFilter[TestException](occurrences = 1).intercept {
        actor ! Fail
      }
      actor ! Ping
      probe.expectMessage(1)
    }

    "not reset behavior upon resume" in {
      val probe = TestProbe[Int]()
      val internal = Behaviors.setup[Command](_ ⇒ {
        var counter = 0
        Behaviors.receivePartial[Command] {
          case (_, Ping) ⇒
            counter += 1
            probe.ref ! counter
            Behavior.same
          case (_, Fail) ⇒
            throw new TestException("Boom")
        }
      }).decorate
      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.resume)
      val actor = spawn(behavior)
      actor ! Ping
      probe.expectMessage(1)
      EventFilter[TestException](occurrences = 1).intercept {
        actor ! Fail
      }
      actor ! Ping
      probe.expectMessage(2)
    }

    "stop upon stop" in {
      val probe = TestProbe[Event]()
      val behavior = (Behaviors.receivePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
        case (_, Fail) ⇒
          throw new TestException("boom")
      } receiveSignal {
        case (_, PostStop) ⇒
          probe.ref ! GotSignal(PostStop)
          Behavior.same
      }).decorate
      val actorToWatch = spawn(behavior)
      val watcher: ActorRef[Command] = spawn((
        Behaviors.receivePartial[Any] {
          case (context, Ping) ⇒
            context.watch(actorToWatch)
            probe.ref ! Pong
            Behavior.same
        } receiveSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behavior.same
        }
      ).decorate)
      actorToWatch ! Ping
      probe.expectMessage(Pong)
      watcher ! Ping
      probe.expectMessage(Pong)
      EventFilter[TestException](occurrences = 1).intercept {
        actorToWatch ! Fail
      }
      probe.expectMessage(GotSignal(PostStop))
      probe.expectTerminated(actorToWatch, timeout.duration)
    }

    "not stop non-child actor" in {
      val probe = TestProbe[Event]()
      val victim = spawn(Behaviors.empty[Command])
      val actor = spawn(Behaviors.receivePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
        case (context, StopRef(ref)) ⇒
          assertThrows[IllegalArgumentException] {
            context.stop(ref)
            probe.ref ! Pong
          }
          probe.ref ! Missed
          Behaviors.same
      }.decorate)
      actor ! Ping
      probe.expectMessage(Pong)
      actor ! StopRef(victim)
      probe.expectMessage(Missed)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "watch a child actor before its termination" in {
      val probe = TestProbe[Event]()
      val child = Behaviors.receivePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      spawn(
        Behaviors.setup[Command](context ⇒ {
          val childRef = context.spawn(child, "A")
          context.watch(childRef)
          probe.ref ! ChildMade(childRef)
          Behaviors.receivePartial[Command] {
            case (_, Ping) ⇒
              probe.ref ! Pong
              Behaviors.same
          } receiveSignal {
            case (_, signal) ⇒
              probe.ref ! GotSignal(signal)
              Behaviors.same
          }
        }).decorate
      )
      val childRef = probe.expectMessageType[ChildMade].ref
      childRef ! Stop
      probe.expectTerminated(childRef, timeout.duration)
    }

    "watch a child actor after its termination" in {
      val probe = TestProbe[Event]()
      val child = Behaviors.receivePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      val actor = spawn(
        Behaviors.setup[Command](context ⇒ {
          val childRef = context.spawn(child, "A")
          probe.ref ! ChildMade(childRef)
          Behaviors.receivePartial[Command] {
            case (context, Watch(ref)) ⇒
              context.watch(ref)
              probe.ref ! Pong
              Behaviors.same
          } receiveSignal {
            case (_, signal) ⇒
              probe.ref ! GotSignal(signal)
              Behaviors.same
          }
        }).decorate
      )
      val childRef = probe.expectMessageType[ChildMade].ref
      actor ! Watch(childRef)
      probe.expectMessage(Pong)
      childRef ! Stop
      probe.expectTerminated(childRef, timeout.duration)
      actor ! Watch(childRef)
      probe.expectTerminated(childRef, timeout.duration)
    }

    "unwatch a child actor before its termination" in {
      val probe = TestProbe[Event]()
      val child = Behaviors.receivePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      val actor = spawn(
        Behaviors.setup[Command](context ⇒ {
          val childRef = context.spawn(child, "A")
          probe.ref ! ChildMade(childRef)
          Behaviors.receivePartial[Command] {
            case (context, Watch(ref)) ⇒
              context.watch(ref)
              probe.ref ! Pong
              Behaviors.same
            case (context, UnWatch(ref)) ⇒
              context.unwatch(ref)
              probe.ref ! Pong
              Behaviors.same
          } receiveSignal {
            case (_, signal) ⇒
              probe.ref ! GotSignal(signal)
              Behaviors.same
          }
        }).decorate
      )
      val childRef = probe.expectMessageType[ChildMade].ref
      actor ! Watch(childRef)
      probe.expectMessage(Pong)
      actor ! UnWatch(childRef)
      probe.expectMessage(Pong)
      childRef ! Stop
      probe.expectNoMessage()
    }

    "terminate upon not handling Terminated" in {
      val probe = TestProbe[Event]()
      val child = (Behaviors.receivePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      } receiveSignal {
        case (_, signal) ⇒
          probe.ref ! GotChildSignal(signal)
          Behavior.same
      }).decorate
      val actor = spawn(
        Behaviors.setup[Command](context ⇒ {
          val childRef = context.spawn(child, "A")
          context.watch(childRef)
          probe.ref ! ChildMade(childRef)
          Behaviors.receivePartial[Command] {
            case (_, Inert) ⇒
              probe.ref ! InertEvent
              Behaviors.receive[Command] {
                case (_, _) ⇒ Behaviors.unhandled
              } receiveSignal {
                case (_, Terminated(_)) ⇒ Behaviors.unhandled
                case (_, signal) ⇒
                  probe.ref ! GotSignal(signal)
                  Behaviors.same
              }
          } receiveSignal {
            case (_, signal) ⇒
              probe.ref ! GotSignal(signal)
              Behaviors.same
          }
        }).decorate
      )
      val childRef = probe.expectMessageType[ChildMade].ref
      actor ! Inert
      probe.expectMessage(InertEvent)
      EventFilter[DeathPactException](occurrences = 1).intercept {
        childRef ! Stop
        probe.expectMessage(GotChildSignal(PostStop))
        probe.expectMessage(GotSignal(PostStop))
        probe.expectTerminated(actor, timeout.duration)
      }
    }

    "return the right context info" in {
      type Info = (ActorSystem[Nothing], ActorRef[String])
      val probe = TestProbe[Info]
      val actor = spawn(Behaviors.receivePartial[String] {
        case (context, "info") ⇒
          probe.ref ! (context.system → context.self)
          Behaviors.same
      }.decorate)
      actor ! "info"
      probe.expectMessage((system, actor))
    }

    "return right info about children" in {
      type Children = Seq[ActorRef[Nothing]]
      val probe = TestProbe[Children]()
      val actor = spawn(Behaviors.receivePartial[String] {
        case (context, "create") ⇒
          context.spawn(Behaviors.empty, "B")
          probe.ref ! context.child("B").toSeq
          Behaviors.same
        case (context, "all") ⇒
          probe.ref ! context.children.toSeq
          Behaviors.same
        case (context, get) ⇒
          probe.ref ! context.child(get).toSeq
          Behaviors.same
      }.decorate)
      actor ! "create"
      val children = probe.expectMessageType[Children]
      actor ! "A"
      probe.expectMessage(Seq.empty)
      actor ! "all"
      probe.expectMessage(children)
      children.size shouldBe 1
      children.head.path.name shouldBe "B"
    }

    "set small receive timeout" in {
      val probe = TestProbe[Event]()
      val actor = spawn(Behaviors.receivePartial[Command] {
        case (_, ReceiveTimeout) ⇒
          probe.ref ! GotReceiveTimeout
          Behaviors.same
        case (context, SetTimeout(duration)) ⇒
          context.setReceiveTimeout(duration, ReceiveTimeout)
          probe.ref ! TimeoutSet
          Behaviors.same
      }.decorate)
      actor ! SetTimeout(1.nano)
      probe.expectMessage(TimeoutSet)
      probe.expectMessage(GotReceiveTimeout)
    }

    "set large receive timeout" in {
      val probe = TestProbe[String]()
      val actor = spawn(Behaviors.receivePartial[String] {
        case (context, "schedule") ⇒
          context.scheduleOnce(1.second, probe.ref, "scheduled")
          Behaviors.same
        case (_, "ping") ⇒
          probe.ref ! "pong"
          Behaviors.same
        case (_, "receive timeout") ⇒
          probe.ref ! "received timeout"
          Behaviors.same
        case (context, duration) ⇒
          context.setReceiveTimeout(Duration(duration).asInstanceOf[FiniteDuration], "receive timeout")
          probe.ref ! "timeout set"
          Behaviors.same
      }.decorate)
      actor ! "1 minute"
      probe.expectMessage("timeout set")
      actor ! "schedule"
      probe.expectMessage("scheduled")
      actor ! "ping"
      probe.expectMessage("pong")
    }

    "schedule a message" in {
      val probe = TestProbe[Event]()
      val actor = spawn(Behaviors.receivePartial[Command] {
        case (context, Ping) ⇒
          context.scheduleOnce(1.nano, probe.ref, Pong)
          Behaviors.same
      }.decorate)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "create a named adapter" in {
      type Envelope = (ActorRef[String], String)
      val messages = TestProbe[Envelope]()
      val probe = TestProbe[ActorRef[String]]()
      val actor = spawn(Behaviors.receivePartial[String] {
        case (context, "message") ⇒
          messages.ref.tell((context.self, "received message"))
          Behaviors.same
        case (context, name) ⇒
          probe.ref ! context.spawnMessageAdapter(identity, name)
          Behaviors.same
      }.decorate)
      val adapterName = "hello"
      actor ! adapterName
      val adapter = probe.expectMessageType[ActorRef[String]]
      adapter.path.name should include(adapterName)
      adapter ! "message"
      messages.expectMessage(actor → "received message")
    }

    "not allow null messages" in {
      val actor = spawn(Behaviors.empty[Null].decorate)
      intercept[InvalidMessageException] {
        actor ! null
      }
    }

    "not have problems stopping already stopped child" in {
      val probe = TestProbe[Event]()
      val actor = spawn(
        Behaviors.setup[Command](context ⇒ {
          val child = context.spawnAnonymous(Behaviors.empty[Command])
          probe.ref ! ChildMade(child)
          Behaviors.receivePartial[Command] {
            case (context, StopRef(ref)) ⇒
              context.stop(ref)
              probe.ref ! Pong
              Behaviors.same
          }
        })
      )
      val child = probe.expectMessageType[ChildMade].ref
      actor ! StopRef(child)
      probe.expectMessage(Pong)
      actor ! StopRef(child)
      probe.expectMessage(Pong)
    }
  }

}

class NormalActorContextSpec extends ActorContextSpec {

  override def decoration[T] = x ⇒ x
}

class WidenActorContextSpec extends ActorContextSpec {

  override def decoration[T] = b ⇒ b.widen { case x ⇒ x }
}

class DeferredActorContextSpec extends ActorContextSpec {

  override def decoration[T] = b ⇒ Behaviors.setup(_ ⇒ b)
}

class NestedDeferredActorContextSpec extends ActorContextSpec {

  override def decoration[T] = b ⇒ Behaviors.setup(_ ⇒ Behaviors.setup(_ ⇒ b))
}

class InterceptActorContextSpec extends ActorContextSpec {
  import BehaviorInterceptor._

  def tap[T] = new BehaviorInterceptor[T, T] {
    override def aroundReceive(context: TypedActorContext[T], message: T, target: ReceiveTarget[T]): Behavior[T] =
      target(context, message)
    override def aroundSignal(context: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] =
      target(context, signal)
  }

  override def decoration[T]: Behavior[T] ⇒ Behavior[T] = b ⇒ Behaviors.intercept[T, T](tap)(b)
}
