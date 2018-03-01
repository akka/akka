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
          probe.ref ! TerminatedRef[Command](ref.upcast[Command])
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
          probe.ref ! TerminatedRef[Command](ref.upcast[Command])
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

    //    "reset behavior upon Restart" in {
    //      sync(setup("ctx05", Some(Behaviors.supervise(_).onFailure(SupervisorStrategy.restart))) { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        val ex = new Exception("KABOOM05")
    //        startWith
    //          .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
    //          .stimulate(_ ! Ping(self), _ ⇒ Pong2) { subj ⇒
    //            muteExpectedException[Exception]("KABOOM05")
    //            subj ! Throw(ex)
    //            subj
    //          }
    //          .stimulate(_ ! Ping(self), _ ⇒ Pong1)
    //      })
    //    }
    //
    //    "not reset behavior upon Resume" in {
    //      sync(setup(
    //        "ctx06",
    //        Some(b ⇒ Behaviors.supervise(b).onFailure(SupervisorStrategy.resume))) { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        val ex = new Exception("KABOOM06")
    //        startWith
    //          .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
    //          .stimulate(_ ! Ping(self), _ ⇒ Pong2).keep { subj ⇒
    //          muteExpectedException[Exception]("KABOOM06", occurrences = 1)
    //          subj ! Throw(ex)
    //        }.stimulate(_ ! Ping(self), _ ⇒ Pong2)
    //      })
    //    }
    //
    //    "stop upon Stop" in {
    //      sync(setup("ctx07", ignorePostStop = false) { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        val ex = new Exception("KABOOM07")
    //        startWith
    //          .stimulate(_ ! Ping(self), _ ⇒ Pong1).keep { subj ⇒
    //          muteExpectedException[Exception]("KABOOM07", occurrences = 1)
    //          subj ! Throw(ex)
    //          ctx.watch(subj)
    //        }.expectMulti(expectTimeout, 2) { (msgs, subj) ⇒
    //          msgs.toSet should ===(Set(Left(Terminated(subj)(null)), Right(GotSignal(PostStop))))
    //        }
    //      })
    //    }
    //
    //    "not stop non-child actor" in {
    //      sync(setup("ctx08") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith.mkChild(Some("A"), ctx.spawnMessageAdapter(ChildEvent), self) {
    //          case (subj, child) ⇒
    //            val other = ctx.spawn(behavior(ctx, ignorePostStop = true), "A")
    //            subj ! Kill(other, ctx.self)
    //            child
    //        }.expectMessageKeep(expectTimeout) { (msg, _) ⇒
    //          msg should ===(NotKilled)
    //        }.stimulate(_ ! Ping(self), _ ⇒ Pong1)
    //      })
    //    }
    //
    //    "watch a child actor before its termination" in {
    //      sync(setup("ctx10") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self) {
    //          case (subj, child) ⇒
    //            subj ! Watch(child, self)
    //            child
    //        }.expectMessageKeep(expectTimeout) { (msg, child) ⇒
    //          msg should ===(Watched)
    //          child ! Stop
    //        }.expectMessage(expectTimeout) { (msg, child) ⇒
    //          msg should ===(GotSignal(Terminated(child)(null)))
    //        }
    //      })
    //    }
    //
    //    "watch a child actor after its termination" in {
    //      sync(setup("ctx11") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self).keep {
    //          case (subj, child) ⇒
    //            ctx.watch(child)
    //            child ! Stop
    //        }.expectTermination(expectTimeout) {
    //          case (t, (subj, child)) ⇒
    //            t should ===(Terminated(child)(null))
    //            subj ! Watch(child, blackhole)
    //            child
    //        }.expectMessage(expectTimeout) { (msg, child) ⇒
    //          msg should ===(GotSignal(Terminated(child)(null)))
    //        }
    //      })
    //    }
    //
    //    "unwatch a child actor before its termination" in {
    //      sync(setup("ctx12") { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self).keep {
    //          case (subj, child) ⇒
    //            subj ! Watch(child, self)
    //        }.expectMessageKeep(expectTimeout) {
    //          case (msg, (subj, child)) ⇒
    //            msg should ===(Watched)
    //            subj ! Unwatch(child, self)
    //        }.expectMessage(expectTimeout) {
    //          case (msg, (subj, child)) ⇒
    //            msg should ===(Unwatched)
    //            ctx.watch(child)
    //            child ! Stop
    //            child
    //        }.expectTermination(expectTimeout) { (t, child) ⇒
    //          t should ===(Terminated(child)(null))
    //        }
    //      })
    //    }
    //
    //    "terminate upon not handling Terminated" in {
    //      sync(setup("ctx13", ignorePostStop = false) { (ctx, startWith) ⇒
    //        val self = ctx.self
    //        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self).keep {
    //          case (subj, child) ⇒
    //            muteExpectedException[DeathPactException]()
    //            subj ! Watch(child, self)
    //        }.expectMessageKeep(expectTimeout) {
    //          case (msg, (subj, child)) ⇒
    //            msg should ===(Watched)
    //            subj ! BecomeCareless(self)
    //        }.expectMessageKeep(expectTimeout) {
    //          case (msg, (subj, child)) ⇒
    //            msg should ===(BecameCareless)
    //            child ! Stop
    //        }.expectMessage(expectTimeout) {
    //          case (msg, (subj, child)) ⇒
    //            msg should ===(ChildEvent(GotSignal(PostStop)))
    //        }.expectMessage(expectTimeout) {
    //          case (msg, _) ⇒
    //            msg should ===(GotSignal(PostStop))
    //        }
    //      })
    //    }
    //
    //    "return the right context info" in {
    //      sync(setup("ctx20") { (ctx, startWith) ⇒
    //        startWith.keep(_ ! GetInfo(ctx.self))
    //          .expectMessage(expectTimeout) {
    //            case (msg: Info, subj) ⇒
    //              msg.self should ===(subj)
    //              msg.system should ===(system)
    //            case (other, _) ⇒
    //              fail(s"$other was not an Info(...)")
    //          }
    //      })
    //    }
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

