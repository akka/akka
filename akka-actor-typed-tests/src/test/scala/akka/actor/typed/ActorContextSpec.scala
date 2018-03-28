/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.InvalidMessageException
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

import scala.concurrent.duration._

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

abstract class ActorContextSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  import ActorSpecMessages._

  def decoration[T]: Behavior[T] ⇒ Behavior[T]

  implicit class BehaviorDecorator[T](behavior: Behavior[T]) {
    def decorate: Behavior[T] = decoration(behavior)
  }

  "An ActorContext" must {

    "be usable from Behavior.interpretMessage" in {
      // compilation only
      lazy val b: Behavior[String] = Behaviors.receive { (ctx, msg) ⇒
        Behavior.interpretMessage(b, ctx, msg)
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
      actor ! Miss
      probe.expectMessage(Missed)
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
      actor ! Fail
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

      val parent: Behavior[Command] = Behaviors.setup[Command](ctx ⇒ {
        val childRef = ctx.spawnAnonymous(
          Behaviors.supervise(child).onFailure(SupervisorStrategy.restart)
        )
        ctx.watch(childRef)
        probe.ref ! ChildMade(childRef)

        (Behaviors.receivePartial[Command] {
          case (ctx, StopRef(ref)) ⇒
            ctx.stop(ref)
            Behavior.same
        } receiveSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behavior.stopped
        }).decorate
      })

      val parentRef = spawn(parent)
      val childRef = probe.expectMessageType[ChildMade].ref
      childRef ! Fail
      probe.expectMessage(GotChildSignal(PreRestart))
      childRef ! Ping
      probe.expectMessage(Pong)
      parentRef ! StopRef(childRef)
      probe.expectTerminated(childRef, timeout.duration)
    }

    "stop a child actor" in {
      val probe = TestProbe[Event]()

      val child: Behavior[Command] = Behaviors.empty[Command].decorate
      val parent: Behavior[Command] = Behaviors.setup[Command](ctx ⇒ {
        val childRef = ctx.spawnAnonymous(child)
        ctx.watch(childRef)
        probe.ref ! ChildMade(childRef)
        Behaviors.receivePartial[Command] {
          case (ctx, StopRef(ref)) ⇒
            ctx.stop(ref)
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
      actor ! Fail
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
      actor ! Fail
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
          case (ctx, Ping) ⇒
            ctx.watch(actorToWatch)
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
      actorToWatch ! Fail
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
      val child = Behaviors.receivePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      }.decorate
      val actor: ActorRef[Command] = spawn(
        Behaviors.setup[Command](ctx ⇒ {
          val childRef = ctx.spawn(child, "A")
          ctx.watch(childRef)
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
        Behaviors.setup[Command](ctx ⇒ {
          val childRef = ctx.spawn(child, "A")
          probe.ref ! ChildMade(childRef)
          Behaviors.receivePartial[Command] {
            case (ctx, Watch(ref)) ⇒
              ctx.watch(ref)
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
        Behaviors.setup[Command](ctx ⇒ {
          val childRef = ctx.spawn(child, "A")
          probe.ref ! ChildMade(childRef)
          Behaviors.receivePartial[Command] {
            case (ctx, Watch(ref)) ⇒
              ctx.watch(ref)
              probe.ref ! Pong
              Behaviors.same
            case (ctx, UnWatch(ref)) ⇒
              ctx.unwatch(ref)
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
        Behaviors.setup[Command](ctx ⇒ {
          val childRef = ctx.spawn(child, "A")
          ctx.watch(childRef)
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
      childRef ! Stop
      probe.expectMessage(GotChildSignal(PostStop))
      probe.expectMessage(GotSignal(PostStop))
      probe.expectTerminated(actor, timeout.duration)
    }

    "return the right context info" in {
      type Info = (ActorSystem[Nothing], ActorRef[String])
      val probe = TestProbe[Info]
      val actor = spawn(Behaviors.receivePartial[String] {
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
      val actor = spawn(Behaviors.receivePartial[String] {
        case (ctx, "create") ⇒
          ctx.spawn(Behaviors.empty, "B")
          probe.ref ! ctx.child("B").toSeq
          Behaviors.same
        case (ctx, "all") ⇒
          probe.ref ! ctx.children.toSeq
          Behaviors.same
        case (ctx, get) ⇒
          probe.ref ! ctx.child(get).toSeq
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
      val probe = TestProbe[String]()
      val actor = spawn(Behaviors.receivePartial[String] {
        case (ctx, "schedule") ⇒
          ctx.schedule(1.second, probe.ref, "scheduled")
          Behaviors.same
        case (_, "ping") ⇒
          probe.ref ! "pong"
          Behaviors.same
        case (_, "receive timeout") ⇒
          probe.ref ! "received timeout"
          Behaviors.same
        case (ctx, duration) ⇒
          ctx.setReceiveTimeout(Duration(duration).asInstanceOf[FiniteDuration], "receive timeout")
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
        case (ctx, Ping) ⇒
          ctx.schedule(1.nano, probe.ref, Pong)
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
        case (ctx, "message") ⇒
          messages.ref.tell((ctx.self, "received message"))
          Behaviors.same
        case (ctx, name) ⇒
          probe.ref ! ctx.spawnMessageAdapter(identity, name)
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
        Behaviors.setup[Command](ctx ⇒ {
          val child = ctx.spawnAnonymous(Behaviors.empty[Command])
          probe.ref ! ChildMade(child)
          Behaviors.receivePartial[Command] {
            case (ctx, StopRef(ref)) ⇒
              ctx.stop(ref)
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
