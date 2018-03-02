/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.actor.typed

import akka.actor.InvalidMessageException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors.BehaviorDecorators
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

import scala.concurrent.duration._

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

case object MakeChild extends Command

case class ChildMade(ref: ActorRef[Command]) extends Event

case object Inert extends Command

case object InertEvent extends Event

case class Watch(ref: ActorRef[Command]) extends Command

case class UnWatch(ref: ActorRef[Command]) extends Command

case object TimeoutSet extends Event

case object ReceiveTimeout extends Command

case class SetTimeout(duration: FiniteDuration) extends Command

case object GotReceiveTimeout extends Event

abstract class ActorContextSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  def decoration[T]: Behavior[T] ⇒ Behavior[T]

  implicit class BehaviorDecorator[T](behavior: Behavior[T]) {
    def decorate: Behavior[T] = decoration(behavior)
  }

  implicit private val testSettings: TestKitSettings = TestKitSettings(system)

  "An ActorContext" must {

    "converge in cyclic behavior" in {
      val probe = TestProbe[Event]()

      lazy val behavior: Behavior[Command] = Behaviors.immutable[Command] { (_, message) ⇒
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
        }
      }.decorate

      val actor = spawn(behavior)
      actor ! Ping
      probe.expectMessage(Pong)
      actor ! Miss
      probe.expectMessage(Missed)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "correctly wire the lifecycle hook" in {
      val probe = TestProbe[Event]()

      val internal = (Behaviors.immutablePartial[Command] {
        case (_, Fail) ⇒
          throw new RuntimeException("Boom")
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotSignal(signal)
          Behaviors.same
      }).decorate

      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.restart)
      val actor = spawn(behavior)
      actor ! Fail
      probe.expectMessage(GotSignal(PreRestart))
    }

    "signal post stop after voluntary termination" in {
      val probe = TestProbe[Event]()

      val behavior: Behavior[Command] = (
        Behaviors.immutablePartial[Command] {
          case (_, Stop) ⇒ Behaviors.stopped
        } onSignal {
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

      val child: Behavior[Command] = (Behaviors.immutablePartial[Command] {
        case (_, Fail) ⇒ throw new RuntimeException("Boom")
        case (_, Inert) ⇒
          probe.ref ! InertEvent
          Behaviors.immutablePartial[Command] {
            case (_, Ping) ⇒
              probe.ref ! Pong
              Behaviors.same
            case _ ⇒ Behaviors.unhandled
          }
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotChildSignal(signal)
          Behavior.stopped
      }).decorate

      val parent: Behavior[Command] = (Behaviors.immutablePartial[Command] {
        case (ctx, MakeChild) ⇒
          val childRef = ctx.spawnAnonymous(
            Behaviors.supervise(child).onFailure(SupervisorStrategy.restart)
          )
          ctx.watch(childRef)
          probe.ref ! ChildMade(childRef)
          Behavior.same
        case (ctx, StopRef(ref)) ⇒
          ctx.stop(ref)
          Behavior.same
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotSignal(signal)
          Behavior.stopped
      }).decorate

      val parentRef = spawn(parent)
      parentRef ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      childRef ! Fail
      probe.expectMessage(GotChildSignal(PreRestart))
      childRef ! Inert
      probe.expectMessage(InertEvent)
      childRef ! Ping
      probe.expectMessage(Pong)
      parentRef ! StopRef(childRef)
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
    }

    "stop a child actor" in {
      val probe = TestProbe[Event]()

      val child: Behavior[Command] = Behaviors.empty[Command].decorate
      val parent: Behavior[Command] = (Behaviors.immutablePartial[Command] {
        case (ctx, MakeChild) ⇒
          val childRef = ctx.spawnAnonymous(
            Behaviors.supervise(child).onFailure(SupervisorStrategy.restart)
          )
          ctx.watch(childRef)
          probe.ref ! ChildMade(childRef)
          Behaviors.same
        case (ctx, StopRef(ref)) ⇒
          ctx.stop(ref)
          Behaviors.same
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotSignal(signal)
          Behavior.stopped
      }).decorate
      val parentRef = spawn(parent)
      parentRef ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      parentRef ! StopRef(childRef)
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
    }

    "reset behavior upon restart" in {
      val probe = TestProbe[Event]()
      val internal = Behaviors.immutablePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behavior.same
        case (_, Fail) ⇒
          throw new RuntimeException("Boom")
      }.decorate
      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.restart)
      val actor = spawn(behavior)
      actor ! Ping
      probe.expectMessage(Pong)
      actor ! Fail
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "reset behavior upon resume" in {
      val probe = TestProbe[Event]()
      val internal = Behaviors.immutablePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behavior.same
        case (_, Fail) ⇒
          throw new RuntimeException("Boom")
      }.decorate
      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.resume)
      val actor = spawn(behavior)
      actor ! Ping
      probe.expectMessage(Pong)
      actor ! Fail
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "stop upon stop" in {
      val probe = TestProbe[Event]()
      val behavior = (Behaviors.immutablePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
        case (_, Fail) ⇒
          throw new RuntimeException("boom")
      } onSignal {
        case (_, PostStop) ⇒
          probe.ref ! GotSignal(PostStop)
          Behavior.same
      }).decorate
      val actorToWatch = spawn(behavior)
      val watcher: ActorRef[Command] = spawn((
        Behaviors.immutablePartial[Any] {
          case (ctx, Ping) ⇒
            ctx.watch(actorToWatch)
            probe.ref ! Pong
            Behavior.same
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behavior.same
        }
      ).decorate)
      actorToWatch ! Ping
      probe.expectMessage(Pong)
      watcher ! Ping
      probe.expectMessage(Pong)
      actorToWatch ! Fail
      probe.expectMessage(GotSignal(PostStop))
      probe.expectMessage(GotSignal(Terminated(actorToWatch)(null)))
    }

    "not stop non-child actor" in {
      val probe = TestProbe[Event]()
      val victim = spawn(Behaviors.empty[Command])
      val actor = spawn(Behaviors.immutablePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
        case (ctx, StopRef(ref)) ⇒
          assertThrows[IllegalArgumentException] {
            ctx.stop(ref)
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
      val child = Behaviors.immutablePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      val actor: ActorRef[Command] = spawn(
        (Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild) ⇒
            val childRef = ctx.spawn(child, "A")
            ctx.watch(childRef)
            probe.ref ! ChildMade(childRef)
            Behaviors.same
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }).decorate
      )
      actor ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      childRef ! Stop
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
    }

    "watch a child actor after its termination" in {
      val probe = TestProbe[Event]()
      val child = Behaviors.immutablePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      val actor = spawn(
        (Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild) ⇒
            val childRef = ctx.spawn(child, "A")
            probe.ref ! ChildMade(childRef)
            Behaviors.same
          case (ctx, Watch(ref)) ⇒
            ctx.watch(ref)
            probe.ref ! Pong
            Behaviors.same
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }).decorate
      )
      actor ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      actor ! Watch(childRef)
      probe.expectMessage(Pong)
      childRef ! Stop
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
      actor ! Watch(childRef)
      probe.expectMessage(Pong)
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
    }

    "unwatch a child actor before its termination" in {
      val probe = TestProbe[Event]()
      val child = Behaviors.immutablePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      val actor = spawn(
        (Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild) ⇒
            val childRef = ctx.spawn(child, "A")
            probe.ref ! ChildMade(childRef)
            Behaviors.same
          case (ctx, Watch(ref)) ⇒
            ctx.watch(ref)
            probe.ref ! Pong
            Behaviors.same
          case (ctx, UnWatch(ref)) ⇒
            ctx.unwatch(ref)
            probe.ref ! Pong
            Behaviors.same
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }).decorate
      )
      actor ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      actor ! Watch(childRef)
      probe.expectMessage(Pong)
      actor ! UnWatch(childRef)
      probe.expectMessage(Pong)
      actor ! Watch(childRef)
      probe.expectMessage(Pong)
      childRef ! Stop
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
    }

    "terminate upon not handling Terminated" in {
      val probe = TestProbe[Event]()
      val child = (Behaviors.immutablePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotChildSignal(signal)
          Behavior.same
      }).decorate
      val actor = spawn(
        (Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild) ⇒
            val childRef = ctx.spawn(child, "A")
            ctx.watch(childRef)
            probe.ref ! ChildMade(childRef)
            Behaviors.same
          case (_, Inert) ⇒
            probe.ref ! InertEvent
            Behaviors.immutable[Command] {
              case (_, _) ⇒ Behaviors.unhandled
            } onSignal {
              case (_, Terminated(_)) ⇒ Behaviors.unhandled
              case (_, signal) ⇒
                probe.ref ! GotSignal(signal)
                Behaviors.same
            }
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }).decorate
      )
      actor ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      actor ! Inert
      probe.expectMessage(InertEvent)
      childRef ! Stop
      probe.expectMessage(GotChildSignal(PostStop))
      probe.expectMessage(GotSignal(PostStop))
    }

    "return the right context info" in {
      type Info = (ActorSystem[Nothing], ActorRef[String])
      val probe = TestProbe[Info]
      val actor = spawn(Behaviors.immutablePartial[String] {
        case (ctx, "info") ⇒
          probe.ref ! (ctx.system → ctx.self)
          Behaviors.same
      }.decorate)
      actor ! "info"
      probe.expectMessage((system, actor))
    }

    "return right info about children" in {
      type Children = Seq[ActorRef[Nothing]]
      val probe = TestProbe[Children]()
      val actor = spawn(Behaviors.immutablePartial[String] {
        case (ctx, "B") ⇒
          ctx.spawn(Behaviors.empty, "B")
          probe.ref ! ctx.child("B").toSeq
          Behaviors.same
        case (ctx, "all") ⇒
          probe.ref ! ctx.children.toSeq
          Behaviors.same
        case (ctx, name) ⇒
          probe.ref ! ctx.child(name).toSeq
          Behaviors.same
      }.decorate)
      actor ! "B"
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
      val actor = spawn(Behaviors.immutablePartial[Command] {
        case (_, ReceiveTimeout) ⇒
          probe.ref ! GotReceiveTimeout
          Behaviors.same
        case (ctx, SetTimeout(duration)) ⇒
          ctx.setReceiveTimeout(duration, ReceiveTimeout)
          probe.ref ! TimeoutSet
          Behaviors.same
      }.decorate)
      actor ! SetTimeout(1.nano)
      probe.expectMessage(TimeoutSet)
      probe.expectMessage(GotReceiveTimeout)
    }

    "set large receive timeout" in {
      val probe = TestProbe[Event]()
      val actor = spawn(Behaviors.immutablePartial[Command] {
        case (ctx, Inert) ⇒
          ctx.schedule(1.second, probe.ref, InertEvent)
          Behaviors.same
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
        case (_, ReceiveTimeout) ⇒
          probe.ref ! GotReceiveTimeout
          Behaviors.same
        case (ctx, SetTimeout(duration)) ⇒
          ctx.setReceiveTimeout(duration, ReceiveTimeout)
          probe.ref ! TimeoutSet
          Behaviors.same
      }.decorate)
      actor ! SetTimeout(1.minute)
      probe.expectMessage(TimeoutSet)
      actor ! Inert
      probe.expectMessage(InertEvent)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "schedule a message" in {
      val probe = TestProbe[Event]()
      val actor = spawn(Behaviors.immutablePartial[Command] {
        case (ctx, Ping) ⇒
          ctx.schedule(1.nano, probe.ref, Pong)
          Behaviors.same
      }.decorate)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "create a named adapter" in {
      val probe = TestProbe[ActorRef[String]]()
      val actor = spawn(Behaviors.immutablePartial[String] {
        case (ctx, name) ⇒
          probe.ref ! ctx.spawnMessageAdapter(identity, name)
          Behaviors.same
      }.decorate)
      val adapterName = "hello"
      actor ! adapterName
      val adapter = probe.expectMessageType[ActorRef[String]]
      adapter.path.name should include(adapterName)
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
        Behaviors.immutablePartial[Command] {
          case (ctx, StopRef(ref)) ⇒
            ctx.stop(ref)
            probe.ref ! Pong
            Behaviors.same
          case (ctx, MakeChild) ⇒
            val child = ctx.spawnAnonymous(Behaviors.empty[Command])
            probe.ref ! ChildMade(child)
            Behaviors.same
        }.decorate
      )
      actor ! MakeChild
      val child = probe.expectMessageType[ChildMade].ref
      actor ! StopRef(child)
      probe.expectMessage(Pong)
      actor ! StopRef(child)
      probe.expectMessage(Pong)
    }
  }

  override def afterAll(): Unit = shutdownTestKit()
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

class TapActorContextSpec extends ActorContextSpec {

  override def decoration[T] = b ⇒ Behaviors.tap((_, _) ⇒ (), (_, _) ⇒ (), b)
}
