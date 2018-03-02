/**
  * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com/>
  */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl.{ActorTestKit, TestProbe}

class NormalActorContextSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  implicit private val testSettings: TestKitSettings = TestKitSettings(system)

  sealed trait Command

  sealed trait Event

  final case object Ping extends Command

  object Pong extends Event

  final case class Renew(replyTo: ActorRef[Renewed.type]) extends Command

  case object Renewed extends Event

  final case object Miss extends Command

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

  case class TerminatedRef[T](ref: ActorRef[T]) extends Event

  case class Watch(ref: ActorRef[Command]) extends Command

  case class UnWatch(ref: ActorRef[Command]) extends Command

  "An ActorContext" must {

    "converge in cyclic behavior" in {
      val probe = TestProbe[Event]()

      lazy val behavior: Behavior[Command] = Behaviors.immutable[Command] { (_, message) ⇒
        message match {
          case Ping       ⇒
            probe.ref ! Pong
            Behaviors.same
          case Miss       ⇒
            probe.ref ! Missed
            Behaviors.unhandled
          case Renew(ref) ⇒
            ref ! Renewed
            behavior
        }
      }

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

      val internal = Behaviors.immutablePartial[Command] {
        case (_, Fail) ⇒
          throw new RuntimeException("Boom")
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotSignal(signal)
          Behaviors.same
      }

      val behavior = Behaviors.supervise(internal).onFailure(SupervisorStrategy.restart)
      val actor = spawn(behavior)
      actor ! Fail
      probe.expectMessage(GotSignal(PreRestart))
    }

    "signal post stop after voluntary termination" in {
      val probe = TestProbe[Event]()

      val behavior: Behavior[Command] =
        Behaviors.immutablePartial[Command] {
          case (_, Stop) ⇒ Behaviors.stopped
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }

      val actor = spawn(behavior)
      actor ! Stop
      probe.expectMessage(GotSignal(PostStop))
    }

    "restart and stop a child actor" in {
      val probe = TestProbe[Event]()

      val child: Behavior[Command] = Behaviors.immutablePartial[Command] {
        case (_, Fail)  ⇒ throw new RuntimeException("Boom")
        case (_, Inert) ⇒
          probe.ref ! InertEvent
          Behaviors.immutablePartial[Command] {
            case (_, Ping) ⇒
              probe.ref ! Pong
              Behaviors.same
            case _         ⇒ Behaviors.unhandled
          }
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotChildSignal(signal)
          Behavior.stopped
      }

      val parent: Behavior[Command] = Behaviors.immutablePartial[Command] {
        case (ctx, MakeChild)    ⇒
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
        case (_, Terminated(ref)) ⇒
          probe.ref ! TerminatedRef(ref.upcast[Command])
          Behavior.stopped
      }

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
      probe.expectMessage(TerminatedRef(childRef))
    }

    "stop a child actor" in {
      val probe = TestProbe[Event]()

      val child: Behavior[Command] = Behaviors.empty[Command]
      val parent: Behavior[Command] = Behaviors.immutablePartial[Command] {
        case (ctx, MakeChild)    ⇒
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
        case (_, Terminated(ref)) ⇒
          probe.ref ! TerminatedRef(ref.upcast[Command])
          Behavior.stopped
      }
      val parentRef = spawn(parent)
      parentRef ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      parentRef ! StopRef(childRef)
      probe.expectMessage(TerminatedRef(childRef))
    }

    "reset behavior upon restart" in {
      val probe = TestProbe[Event]()
      val internal: Behavior[Command] = Behaviors.immutablePartial {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behavior.same
        case (_, Fail) ⇒
          throw new RuntimeException("Boom")
      }
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
      val internal: Behavior[Command] = Behaviors.immutablePartial {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behavior.same
        case (_, Fail) ⇒
          throw new RuntimeException("Boom")
      }
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
      val behavior: Behavior[Command] = Behaviors.immutablePartial[Command] {
        case (_, Ping) ⇒
          probe.ref ! Pong
          Behaviors.same
        case (_, Fail) ⇒
          throw new RuntimeException("boom")
      } onSignal {
        case (_, PostStop) ⇒
          probe.ref ! GotSignal(PostStop)
          Behavior.same
      }
      val actorToWatch = spawn(behavior)
      val watcher: ActorRef[Command] = spawn(
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
      )
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
      val actor: ActorRef[Command] = spawn(Behaviors.immutablePartial[Command] {
        case (_, Ping)           ⇒
          probe.ref ! Pong
          Behaviors.same
        case (ctx, StopRef(ref)) ⇒
          assertThrows[IllegalArgumentException] {
            ctx.stop(ref)
            probe.ref ! Pong
          }
          probe.ref ! Missed
          Behaviors.same
      })
      actor ! Ping
      probe.expectMessage(Pong)
      actor ! StopRef(victim)
      probe.expectMessage(Missed)
      actor ! Ping
      probe.expectMessage(Pong)
    }

    "watch a child actor before its termination" in {
      val probe = TestProbe[Event]()
      val child: Behavior[Command] = Behaviors.immutablePartial {
        case (_, Stop) ⇒
          Behaviors.stopped
      }
      val actor: ActorRef[Command] = spawn(
        Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild) ⇒
            val childRef = ctx.spawn(child, "A")
            ctx.watch(childRef)
            probe.ref ! ChildMade(childRef)
            Behaviors.same
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }
      )
      actor ! MakeChild
      val childRef = probe.expectMessageType[ChildMade].ref
      childRef ! Stop
      probe.expectMessage(GotSignal(Terminated(childRef)(null)))
    }

    "watch a child actor after its termination" in {
      val probe = TestProbe[Event]()
      val child: Behavior[Command] = Behaviors.immutablePartial {
        case (_, Stop) ⇒
          Behaviors.stopped
      }
      val actor: ActorRef[Command] = spawn(
        Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild)  ⇒
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
        }
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
      val child: Behavior[Command] = Behaviors.immutablePartial {
        case (_, Stop) ⇒
          Behaviors.stopped
      }
      val actor: ActorRef[Command] = spawn(
        Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild)    ⇒
            val childRef = ctx.spawn(child, "A")
            probe.ref ! ChildMade(childRef)
            Behaviors.same
          case (ctx, Watch(ref))   ⇒
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
        }
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
      val child: Behavior[Command] = Behaviors.immutablePartial[Command] {
        case (_, Stop) ⇒
          Behaviors.stopped
      } onSignal {
        case (_, signal) ⇒
          probe.ref ! GotChildSignal(signal)
          Behavior.same
      }
      val actor: ActorRef[Command] = spawn(
        Behaviors.immutablePartial[Command] {
          case (ctx, MakeChild) ⇒
            val childRef = ctx.spawn(child, "A")
            ctx.watch(childRef)
            probe.ref ! ChildMade(childRef)
            Behaviors.same
          case (_, Inert)       ⇒
            probe.ref ! InertEvent
            Behaviors.immutable[Command] {
              case (_, _) ⇒ Behaviors.unhandled
            } onSignal {
              case (_, Terminated(_)) ⇒ Behaviors.unhandled
              case (_, signal)        ⇒
                probe.ref ! GotSignal(signal)
                Behaviors.same
            }
        } onSignal {
          case (_, signal) ⇒
            probe.ref ! GotSignal(signal)
            Behaviors.same
        }
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
      val actor: ActorRef[String] = spawn(Behaviors.immutablePartial[String] {
        case (ctx, "info") ⇒
          probe.ref ! (ctx.system → ctx.self)
          Behaviors.same
      })
      actor ! "info"
      probe.expectMessage((system, actor))
    }

    //
    //    "return right info about children" in {
    //      sync(setup("ctx21") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith
    //          .mkChild(Some("B"), ctx.spawnMessageAdapter(ChildEvent), self)
    //          .stimulate(_._1 ! GetChild("A", self), _ ⇒ Child(None))
    //          .stimulate(_._1 ! GetChild("B", self), x ⇒ Child(Some(x._2)))
    //          .stimulate(_._1 ! GetChildren(self), x ⇒ Children(Set(x._2)))
    //      })
    //    }
    //
    //    "set small receive timeout" in {
    //      sync(setup("ctx30") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith
    //          .stimulate(_ ! SetTimeout(1.nano, self), _ ⇒ TimeoutSet)
    //          .expectMessage(expectTimeout) { (msg, _) ⇒
    //            msg should ===(GotReceiveTimeout)
    //          }
    //      })
    //    }
    //
    //    "set large receive timeout" in {
    //      sync(setup("ctx31") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith
    //          .stimulate(_ ! SetTimeout(1.minute, self), _ ⇒ TimeoutSet)
    //          .stimulate(_ ⇒ ctx.schedule(1.second, self, Pong2), _ ⇒ Pong2)
    //          .stimulate(_ ! Ping(self), _ ⇒ Pong1)
    //
    //      })
    //    }
    //
    //    "schedule a message" in {
    //      sync(setup("ctx32") { (ctx, startWith) ⇒
    //        startWith(_ ! Schedule(1.nano, ctx.self, Pong2, ctx.self))
    //          .expectMultipleMessages(expectTimeout, 2) { (msgs, _) ⇒
    //            msgs should ===(Scheduled :: Pong2 :: Nil)
    //          }
    //      })
    //    }
    //
    //    "create a working adapter" in {
    //      sync(setup("ctx40", ignorePostStop = false) { (ctx, startWith) ⇒
    //        startWith.keep { subj ⇒
    //          subj ! GetAdapter(ctx.self)
    //        }.expectMessage(expectTimeout) { (msg, subj) ⇒
    //          val Adapter(adapter) = msg
    //          ctx.watch(adapter)
    //          adapter ! Ping(ctx.self)
    //          (subj, adapter)
    //        }.expectMessage(expectTimeout) {
    //          case (msg, (subj, adapter)) ⇒
    //            msg should ===(Pong1)
    //            ctx.stop(subj)
    //            adapter
    //        }.expectMulti(expectTimeout, 2) { (msgs, adapter) ⇒
    //          msgs.toSet should ===(Set(Left(Terminated(adapter)(null)), Right(GotSignal(PostStop))))
    //        }
    //      })
    //    }
    //
    //    "create a named adapter" in {
    //      sync(setup("ctx41") { (ctx, startWith) ⇒
    //        startWith.keep { subj ⇒
    //          subj ! GetAdapter(ctx.self, "named")
    //        }.expectMessage(expectTimeout) { (msg, subj) ⇒
    //          val Adapter(adapter) = msg
    //          adapter.path.name should include("named")
    //        }
    //      })
    //    }
    //
    //    "not allow null messages" in {
    //      sync(setup("ctx42") { (ctx, startWith) ⇒
    //        startWith.keep { subj ⇒
    //          intercept[InvalidMessageException] {
    //            subj ! null
    //          }
    //        }
    //      })
    //    }
    //
    //    "not have problems stopping already stopped child" in {
    //      sync(setup("ctx45", ignorePostStop = false) { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith.mkChild(Some("A"), ctx.spawnMessageAdapter(ChildEvent), self, inert = true) {
    //          case (subj, child) ⇒
    //            subj ! Kill(child, self)
    //            (subj, child)
    //        }.expectMessageKeep(expectTimeout) {
    //          case (msg, (subj, child)) ⇒
    //            msg should ===(Killed)
    //            (subj, ctx.watch(child))
    //        }.expectTermination(expectTimeout) {
    //          case (t, (subj, child)) ⇒
    //            t.ref should ===(child)
    //            subj ! Kill(child, self)
    //            child
    //        }.expectMessage(expectTimeout) {
    //          case (msg, _) ⇒
    //            msg should ===(Killed)
    //        }
    //      })
    //    }
    //
    //  }
  }
}

