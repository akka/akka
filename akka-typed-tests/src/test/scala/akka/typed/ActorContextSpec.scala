/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory
import akka.actor.DeadLetterSuppression
import akka.typed.scaladsl.Actor
import scala.language.existentials

object ActorContextSpec {

  sealed trait Command
  sealed trait Event
  sealed trait Monitor extends Event

  final case class GotSignal(signal: Signal) extends Monitor with DeadLetterSuppression
  final case object GotReceiveTimeout extends Monitor

  final case object ReceiveTimeout extends Command

  final case class Ping(replyTo: ActorRef[Pong]) extends Command
  sealed trait Pong extends Event
  case object Pong1 extends Pong
  case object Pong2 extends Pong

  final case class Miss(replyTo: ActorRef[Missed.type]) extends Command
  case object Missed extends Event

  final case class Renew(replyTo: ActorRef[Renewed.type]) extends Command
  case object Renewed extends Event

  final case class Throw(ex: Exception) extends Command

  final case class MkChild(name: Option[String], monitor: ActorRef[Monitor], replyTo: ActorRef[Created]) extends Command
  final case class Created(ref: ActorRef[Command]) extends Event

  final case class SetTimeout(duration: FiniteDuration, replyTo: ActorRef[TimeoutSet.type]) extends Command
  case object TimeoutSet extends Event

  final case class Schedule[T](delay: FiniteDuration, target: ActorRef[T], msg: T, replyTo: ActorRef[Scheduled.type]) extends Command
  case object Scheduled extends Event

  case object Stop extends Command

  final case class Kill(ref: ActorRef[Nothing], replyTo: ActorRef[KillResult]) extends Command
  sealed trait KillResult extends Event
  case object Killed extends KillResult
  case object NotKilled extends KillResult

  final case class Watch(ref: ActorRef[Nothing], replyTo: ActorRef[Watched.type]) extends Command
  case object Watched extends Event

  final case class Unwatch(ref: ActorRef[Nothing], replyTo: ActorRef[Unwatched.type]) extends Command
  case object Unwatched extends Event

  final case class GetInfo(replyTo: ActorRef[Info]) extends Command
  final case class Info(self: ActorRef[Command], system: ActorSystem[Nothing]) extends Event

  final case class GetChild(name: String, replyTo: ActorRef[Child]) extends Command
  final case class Child(c: Option[ActorRef[Nothing]]) extends Event

  final case class GetChildren(replyTo: ActorRef[Children]) extends Command
  final case class Children(c: Set[ActorRef[Nothing]]) extends Event

  final case class ChildEvent(event: Event) extends Event

  final case class BecomeInert(replyTo: ActorRef[BecameInert.type]) extends Command
  case object BecameInert extends Event

  final case class BecomeCareless(replyTo: ActorRef[BecameCareless.type]) extends Command
  case object BecameCareless extends Event

  final case class GetAdapter(replyTo: ActorRef[Adapter], name: String = "") extends Command
  final case class Adapter(a: ActorRef[Command]) extends Event

  def subject(monitor: ActorRef[Monitor], ignorePostStop: Boolean): Behavior[Command] =
    Actor.immutable[Command] {
      (ctx, message) ⇒
        message match {
          case ReceiveTimeout ⇒
            monitor ! GotReceiveTimeout
            Actor.same
          case Ping(replyTo) ⇒
            replyTo ! Pong1
            Actor.same
          case Miss(replyTo) ⇒
            replyTo ! Missed
            Actor.unhandled
          case Renew(replyTo) ⇒
            replyTo ! Renewed
            subject(monitor, ignorePostStop)
          case Throw(ex) ⇒
            throw ex
          case MkChild(name, mon, replyTo) ⇒
            val child = name match {
              case None    ⇒ ctx.spawnAnonymous(Actor.supervise(subject(mon, ignorePostStop)).onFailure(SupervisorStrategy.restart))
              case Some(n) ⇒ ctx.spawn(Actor.supervise(subject(mon, ignorePostStop)).onFailure(SupervisorStrategy.restart), n)
            }
            replyTo ! Created(child)
            Actor.same
          case SetTimeout(d, replyTo) ⇒
            d match {
              case f: FiniteDuration ⇒ ctx.setReceiveTimeout(f, ReceiveTimeout)
              case _                 ⇒ ctx.cancelReceiveTimeout()
            }
            replyTo ! TimeoutSet
            Actor.same
          case Schedule(delay, target, msg, replyTo) ⇒
            replyTo ! Scheduled
            ctx.schedule(delay, target, msg)
            Actor.same
          case Stop ⇒
            Actor.stopped
          case Kill(ref, replyTo) ⇒
            if (ctx.stop(ref)) replyTo ! Killed
            else replyTo ! NotKilled
            Actor.same
          case Watch(ref, replyTo) ⇒
            ctx.watch(ref)
            replyTo ! Watched
            Actor.same
          case Unwatch(ref, replyTo) ⇒
            ctx.unwatch(ref)
            replyTo ! Unwatched
            Actor.same
          case GetInfo(replyTo) ⇒
            replyTo ! Info(ctx.self, ctx.system)
            Actor.same
          case GetChild(name, replyTo) ⇒
            replyTo ! Child(ctx.child(name))
            Actor.same
          case GetChildren(replyTo) ⇒
            replyTo ! Children(ctx.children.toSet)
            Actor.same
          case BecomeInert(replyTo) ⇒
            replyTo ! BecameInert
            Actor.immutable {
              case (_, Ping(replyTo)) ⇒
                replyTo ! Pong2
                Actor.same
              case (_, Throw(ex)) ⇒
                throw ex
              case _ ⇒ Actor.unhandled
            }
          case BecomeCareless(replyTo) ⇒
            replyTo ! BecameCareless
            Actor.immutable[Command] {
              case (_, _) ⇒ Actor.unhandled
            } onSignal {
              case (_, PostStop) if ignorePostStop ⇒ Actor.same // ignore PostStop here
              case (_, Terminated(_))              ⇒ Actor.unhandled
              case (_, sig) ⇒
                monitor ! GotSignal(sig)
                Actor.same
            }
          case GetAdapter(replyTo, name) ⇒
            replyTo ! Adapter(ctx.spawnAdapter(identity, name))
            Actor.same
        }
    } onSignal {
      case (_, PostStop) if ignorePostStop ⇒ Actor.same // ignore PostStop here
      case (ctx, signal)                   ⇒ monitor ! GotSignal(signal); Actor.same
    }

  def oldSubject(monitor: ActorRef[Monitor], ignorePostStop: Boolean): Behavior[Command] = {
    Actor.immutable[Command] {
      case (ctx, message) ⇒ message match {
        case ReceiveTimeout ⇒
          monitor ! GotReceiveTimeout
          Actor.same
        case Ping(replyTo) ⇒
          replyTo ! Pong1
          Actor.same
        case Miss(replyTo) ⇒
          replyTo ! Missed
          Actor.unhandled
        case Renew(replyTo) ⇒
          replyTo ! Renewed
          subject(monitor, ignorePostStop)
        case Throw(ex) ⇒
          throw ex
        case MkChild(name, mon, replyTo) ⇒
          val child = name match {
            case None    ⇒ ctx.spawnAnonymous(Actor.supervise(subject(mon, ignorePostStop)).onFailure[Throwable](SupervisorStrategy.restart))
            case Some(n) ⇒ ctx.spawn(Actor.supervise(subject(mon, ignorePostStop)).onFailure[Throwable](SupervisorStrategy.restart), n)
          }
          replyTo ! Created(child)
          Actor.same
        case SetTimeout(d, replyTo) ⇒
          d match {
            case f: FiniteDuration ⇒ ctx.setReceiveTimeout(f, ReceiveTimeout)
            case _                 ⇒ ctx.cancelReceiveTimeout()
          }
          replyTo ! TimeoutSet
          Actor.same
        case Schedule(delay, target, msg, replyTo) ⇒
          replyTo ! Scheduled
          ctx.schedule(delay, target, msg)
          Actor.same
        case Stop ⇒
          Actor.stopped
        case Kill(ref, replyTo) ⇒
          if (ctx.stop(ref)) replyTo ! Killed
          else replyTo ! NotKilled
          Actor.same
        case Watch(ref, replyTo) ⇒
          ctx.watch(ref)
          replyTo ! Watched
          Actor.same
        case Unwatch(ref, replyTo) ⇒
          ctx.unwatch(ref)
          replyTo ! Unwatched
          Actor.same
        case GetInfo(replyTo) ⇒
          replyTo ! Info(ctx.self, ctx.system)
          Actor.same
        case GetChild(name, replyTo) ⇒
          replyTo ! Child(ctx.child(name))
          Actor.same
        case GetChildren(replyTo) ⇒
          replyTo ! Children(ctx.children.toSet)
          Actor.same
        case BecomeInert(replyTo) ⇒
          replyTo ! BecameInert
          Actor.immutable[Command] {
            case (_, Ping(replyTo)) ⇒
              replyTo ! Pong2
              Actor.same
            case (_, Throw(ex)) ⇒
              throw ex
            case _ ⇒ Actor.same
          }
        case BecomeCareless(replyTo) ⇒
          replyTo ! BecameCareless
          Actor.immutable[Command] {
            case _ ⇒ Actor.unhandled
          } onSignal {
            case (_, PostStop) if ignorePostStop ⇒ Actor.same // ignore PostStop here
            case (_, Terminated(_))              ⇒ Actor.unhandled
            case (_, sig) ⇒
              monitor ! GotSignal(sig)
              Actor.same
          }
        case GetAdapter(replyTo, name) ⇒
          replyTo ! Adapter(ctx.spawnAdapter(identity, name))
          Actor.same
      }
    } onSignal {
      case (_, PostStop) if ignorePostStop ⇒ Actor.same // ignore PostStop here
      case (_, signal) ⇒
        monitor ! GotSignal(signal)
        Actor.same
    }
  }

}

class ActorContextSpec extends TypedSpec(ConfigFactory.parseString(
  """|akka {
     |  loglevel = WARNING
     |  actor.debug {
     |    lifecycle = off
     |    autoreceive = off
     |  }
     |  typed.loggers = ["akka.typed.testkit.TestEventListener"]
     |}""".stripMargin)) {
  import ActorContextSpec._

  val expectTimeout = 3.seconds

  trait Tests {
    /**
     * The name for the set of tests to be instantiated, used for keeping the test case actors’ names unique.
     */
    def suite: String

    /**
     * The behavior against which to run all the tests.
     */
    def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command]

    implicit def system: ActorSystem[TypedSpec.Command]

    private def mySuite: String =
      if (system eq nativeSystem) suite + "Native"
      else suite + "Adapted"

    def setup(name: String, wrapper: Option[Behavior[Command] ⇒ Behavior[Command]] = None, ignorePostStop: Boolean = true)(
      proc: (scaladsl.ActorContext[Event], StepWise.Steps[Event, ActorRef[Command]]) ⇒ StepWise.Steps[Event, _]): Future[TypedSpec.Status] =
      runTest(s"$mySuite-$name")(StepWise[Event] { (ctx, startWith) ⇒
        val b = behavior(ctx, ignorePostStop)
        val props = wrapper.map(_(b)).getOrElse(b)
        val steps = startWith.withKeepTraces(true)(ctx.spawn(props, "subject"))

        proc(ctx, steps)
      })

    private implicit class MkC(val startWith: StepWise.Steps[Event, ActorRef[Command]]) {
      /**
       * Ask the subject to create a child actor, setting its behavior to “inert” if requested.
       * The latter is very useful in order to avoid disturbances with GotSignal(PostStop) in
       * test procedures that stop this child.
       */
      def mkChild(
        name:    Option[String],
        monitor: ActorRef[Event],
        self:    ActorRef[Event],
        inert:   Boolean         = false): StepWise.Steps[Event, (ActorRef[Command], ActorRef[Command])] = {
        val s =
          startWith.keep { subj ⇒
            subj ! MkChild(name, monitor, self)
          }.expectMessage(expectTimeout) { (msg, subj) ⇒
            val Created(child) = msg
            (subj, child)
          }

        if (!inert) s
        else
          s.keep {
            case (subj, child) ⇒
              child ! BecomeInert(self)
          }.expectMessageKeep(expectTimeout) { (msg, _) ⇒
            msg should ===(BecameInert)
          }
      }
    }

    private implicit class MessageStep[T](val startWith: StepWise.Steps[Event, T]) {
      def stimulate(f: T ⇒ Unit, ev: T ⇒ Event, timeout: FiniteDuration = expectTimeout): StepWise.Steps[Event, T] =
        startWith.keep(f).expectMessageKeep(timeout) { (msg, v) ⇒
          msg should ===(ev(v))
        }
    }

    protected def stop(ref: ActorRef[Command]) = ref ! Stop

    def `00 must canonicalize behaviors`(): Unit = sync(setup("ctx00") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.keep { subj ⇒
        subj ! Ping(self)
      }.expectMessageKeep(expectTimeout) { (msg, subj) ⇒
        msg should ===(Pong1)
        subj ! Miss(self)
      }.expectMessageKeep(expectTimeout) { (msg, subj) ⇒
        msg should ===(Missed)
        subj ! Renew(self)
      }.expectMessage(expectTimeout) { (msg, subj) ⇒
        msg should ===(Renewed)
        subj ! Ping(self)
      }.expectMessage(expectTimeout) { (msg, _) ⇒
        msg should ===(Pong1)
      }
    })

    def `01 must correctly wire the lifecycle hooks`(): Unit =
      sync(setup("ctx01", Some(b ⇒ Actor.supervise(b).onFailure[Throwable](SupervisorStrategy.restart)), ignorePostStop = false) { (ctx, startWith) ⇒
        val self = ctx.self
        val ex = new Exception("KABOOM1")
        startWith { subj ⇒
          val log = muteExpectedException[Exception]("KABOOM1", occurrences = 1)
          subj ! Throw(ex)
          (subj, log)
        }.expectMessage(expectTimeout) {
          case (msg, (subj, log)) ⇒
            msg should ===(GotSignal(PreRestart))
            log.assertDone(expectTimeout)
            ctx.stop(subj)
        }.expectMessage(expectTimeout) { (msg, _) ⇒
          msg should ===(GotSignal(PostStop))
        }
      })

    def `02 must signal PostStop after voluntary termination`(): Unit = sync(setup("ctx02", ignorePostStop = false) { (ctx, startWith) ⇒
      startWith.keep { subj ⇒
        stop(subj)
      }.expectMessage(expectTimeout) {
        case (msg, _) ⇒
          msg should ===(GotSignal(PostStop))
      }
    })

    def `03 must restart and stop a child actor`(): Unit = sync(setup("ctx03") { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM2")
      startWith.mkChild(None, ctx.spawnAdapter(ChildEvent), self) {
        case (subj, child) ⇒
          val log = muteExpectedException[Exception]("KABOOM2", occurrences = 1)
          child ! Throw(ex)
          (subj, child, log)
      }.expectMessage(expectTimeout) {
        case (msg, (subj, child, log)) ⇒
          msg should ===(ChildEvent(GotSignal(PreRestart)))
          log.assertDone(expectTimeout)
          child ! BecomeInert(self) // necessary to avoid PostStop/Terminated interference
          (subj, child)
      }.expectMessageKeep(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(BecameInert)
          stop(subj)
          ctx.watch(child)
          ctx.watch(subj)
      }.expectTermination(expectTimeout) {
        case (t, (subj, child)) ⇒
          if (t.ref === child) subj
          else if (t.ref === subj) child
          else fail(s"expected termination of either $subj or $child but got $t")
      }.expectTermination(expectTimeout) { (t, subj) ⇒
        t.ref should ===(subj)
      }
    })

    def `04 must stop a child actor`(): Unit = sync(setup("ctx04") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(Some("A"), ctx.spawnAdapter(ChildEvent), self, inert = true) {
        case (subj, child) ⇒
          subj ! Kill(child, self)
          child
      }.expectMessageKeep(expectTimeout) { (msg, child) ⇒
        msg should ===(Killed)
        ctx.watch(child)
      }.expectTermination(expectTimeout) { (t, child) ⇒
        t.ref should ===(child)
      }
    })

    def `05 must reset behavior upon Restart`(): Unit = sync(setup("ctx05", Some(Actor.supervise(_).onFailure(SupervisorStrategy.restart))) { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM05")
      startWith
        .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
        .stimulate(_ ! Ping(self), _ ⇒ Pong2) { subj ⇒
          val log = muteExpectedException[Exception]("KABOOM05")
          subj ! Throw(ex)
          subj
        }
        .stimulate(_ ! Ping(self), _ ⇒ Pong1)
    })

    def `06 must not reset behavior upon Resume`(): Unit = sync(setup(
      "ctx06",
      Some(b ⇒ Actor.supervise(b).onFailure(SupervisorStrategy.resume))) { (ctx, startWith) ⇒
        val self = ctx.self
        val ex = new Exception("KABOOM06")
        startWith
          .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
          .stimulate(_ ! Ping(self), _ ⇒ Pong2).keep { subj ⇒
            muteExpectedException[Exception]("KABOOM06", occurrences = 1)
            subj ! Throw(ex)
          }.stimulate(_ ! Ping(self), _ ⇒ Pong2)
      })

    def `07 must stop upon Stop`(): Unit = sync(setup("ctx07", ignorePostStop = false) { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM07")
      startWith
        .stimulate(_ ! Ping(self), _ ⇒ Pong1).keep { subj ⇒
          muteExpectedException[Exception]("KABOOM07", occurrences = 1)
          subj ! Throw(ex)
          ctx.watch(subj)
        }.expectMulti(expectTimeout, 2) { (msgs, subj) ⇒
          msgs.toSet should ===(Set(Left(Terminated(subj)(null)), Right(GotSignal(PostStop))))
        }
    })

    def `08 must not stop non-child actor`(): Unit = sync(setup("ctx08") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(Some("A"), ctx.spawnAdapter(ChildEvent), self) {
        case (subj, child) ⇒
          val other = ctx.spawn(behavior(ctx, ignorePostStop = true), "A")
          subj ! Kill(other, ctx.self)
          child
      }.expectMessageKeep(expectTimeout) { (msg, _) ⇒
        msg should ===(NotKilled)
      }.stimulate(_ ! Ping(self), _ ⇒ Pong1)
    })

    def `10 must watch a child actor before its termination`(): Unit = sync(setup("ctx10") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(None, ctx.spawnAdapter(ChildEvent), self) {
        case (subj, child) ⇒
          subj ! Watch(child, self)
          child
      }.expectMessageKeep(expectTimeout) { (msg, child) ⇒
        msg should ===(Watched)
        child ! Stop
      }.expectMessage(expectTimeout) { (msg, child) ⇒
        msg should ===(GotSignal(Terminated(child)(null)))
      }
    })

    def `11 must watch a child actor after its termination`(): Unit = sync(setup("ctx11") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(None, ctx.spawnAdapter(ChildEvent), self).keep {
        case (subj, child) ⇒
          ctx.watch(child)
          child ! Stop
      }.expectTermination(expectTimeout) {
        case (t, (subj, child)) ⇒
          t should ===(Terminated(child)(null))
          subj ! Watch(child, blackhole)
          child
      }.expectMessage(expectTimeout) { (msg, child) ⇒
        msg should ===(GotSignal(Terminated(child)(null)))
      }
    })

    def `12 must unwatch a child actor before its termination`(): Unit = sync(setup("ctx12") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(None, ctx.spawnAdapter(ChildEvent), self).keep {
        case (subj, child) ⇒
          subj ! Watch(child, self)
      }.expectMessageKeep(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(Watched)
          subj ! Unwatch(child, self)
      }.expectMessage(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(Unwatched)
          ctx.watch(child)
          child ! Stop
          child
      }.expectTermination(expectTimeout) { (t, child) ⇒
        t should ===(Terminated(child)(null))
      }
    })

    def `13 must terminate upon not handling Terminated`(): Unit = sync(setup("ctx13", ignorePostStop = false) { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(None, ctx.spawnAdapter(ChildEvent), self).keep {
        case (subj, child) ⇒
          muteExpectedException[DeathPactException]()
          subj ! Watch(child, self)
      }.expectMessageKeep(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(Watched)
          subj ! BecomeCareless(self)
      }.expectMessageKeep(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(BecameCareless)
          child ! Stop
      }.expectMessage(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(ChildEvent(GotSignal(PostStop)))
      }.expectMessage(expectTimeout) {
        case (msg, _) ⇒
          msg should ===(GotSignal(PostStop))
      }
    })

    def `20 must return the right context info`(): Unit = sync(setup("ctx20") { (ctx, startWith) ⇒
      startWith.keep(_ ! GetInfo(ctx.self))
        .expectMessage(expectTimeout) {
          case (msg: Info, subj) ⇒
            msg.self should ===(subj)
            msg.system should ===(system)
          case (other, _) ⇒
            fail(s"$other was not an Info(...)")
        }
    })

    def `21 must return right info about children`(): Unit = sync(setup("ctx21") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith
        .mkChild(Some("B"), ctx.spawnAdapter(ChildEvent), self)
        .stimulate(_._1 ! GetChild("A", self), _ ⇒ Child(None))
        .stimulate(_._1 ! GetChild("B", self), x ⇒ Child(Some(x._2)))
        .stimulate(_._1 ! GetChildren(self), x ⇒ Children(Set(x._2)))
    })

    def `30 must set small receive timeout`(): Unit = sync(setup("ctx30") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith
        .stimulate(_ ! SetTimeout(1.nano, self), _ ⇒ TimeoutSet)
        .expectMessage(expectTimeout) { (msg, _) ⇒
          msg should ===(GotReceiveTimeout)
        }
    })

    def `31 must set large receive timeout`(): Unit = sync(setup("ctx31") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith
        .stimulate(_ ! SetTimeout(1.minute, self), _ ⇒ TimeoutSet)
        .stimulate(_ ⇒ ctx.schedule(1.second, self, Pong2), _ ⇒ Pong2)
        .stimulate(_ ! Ping(self), _ ⇒ Pong1)
    })

    def `32 must schedule a message`(): Unit = sync(setup("ctx32") { (ctx, startWith) ⇒
      startWith(_ ! Schedule(1.nano, ctx.self, Pong2, ctx.self))
        .expectMultipleMessages(expectTimeout, 2) { (msgs, _) ⇒
          msgs should ===(Scheduled :: Pong2 :: Nil)
        }
    })

    def `40 must create a working adapter`(): Unit = sync(setup("ctx40", ignorePostStop = false) { (ctx, startWith) ⇒
      startWith.keep { subj ⇒
        subj ! GetAdapter(ctx.self)
      }.expectMessage(expectTimeout) { (msg, subj) ⇒
        val Adapter(adapter) = msg
        ctx.watch(adapter)
        adapter ! Ping(ctx.self)
        (subj, adapter)
      }.expectMessage(expectTimeout) {
        case (msg, (subj, adapter)) ⇒
          msg should ===(Pong1)
          ctx.stop(subj)
          adapter
      }.expectMulti(expectTimeout, 2) { (msgs, adapter) ⇒
        msgs.toSet should ===(Set(Left(Terminated(adapter)(null)), Right(GotSignal(PostStop))))
      }
    })

    def `41 must create a named adapter`(): Unit = sync(setup("ctx41") { (ctx, startWith) ⇒
      startWith.keep { subj ⇒
        subj ! GetAdapter(ctx.self, "named")
      }.expectMessage(expectTimeout) { (msg, subj) ⇒
        val Adapter(adapter) = msg
        adapter.path.name should include("named")
      }
    })
  }

  trait Normal extends Tests {
    override def suite = "normal"
    override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
      subject(ctx.self, ignorePostStop)
  }
  object `An ActorContext (native)` extends Normal with NativeSystem
  object `An ActorContext (adapted)` extends Normal with AdaptedSystem

  trait Widened extends Tests {
    import Actor._
    override def suite = "widened"
    override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
      subject(ctx.self, ignorePostStop).widen { case x ⇒ x }
  }
  object `An ActorContext with widened Behavior (native)` extends Widened with NativeSystem
  object `An ActorContext with widened Behavior (adapted)` extends Widened with AdaptedSystem

  trait Deferred extends Tests {
    override def suite = "deferred"
    override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
      Actor.deferred(_ ⇒ subject(ctx.self, ignorePostStop))
  }
  object `An ActorContext with deferred Behavior (native)` extends Deferred with NativeSystem
  object `An ActorContext with deferred Behavior (adapted)` extends Deferred with AdaptedSystem

  trait NestedDeferred extends Tests {
    override def suite = "deferred"
    override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
      Actor.deferred(_ ⇒ Actor.deferred(_ ⇒ subject(ctx.self, ignorePostStop)))
  }
  object `An ActorContext with nested deferred Behavior (native)` extends NestedDeferred with NativeSystem
  object `An ActorContext with nested deferred Behavior (adapted)` extends NestedDeferred with AdaptedSystem

  trait Tap extends Tests {
    override def suite = "tap"
    override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
      Actor.tap((_, _) ⇒ (), (_, _) ⇒ (), subject(ctx.self, ignorePostStop))
  }
  object `An ActorContext with Tap (old-native)` extends Tap with NativeSystem
  object `An ActorContext with Tap (old-adapted)` extends Tap with AdaptedSystem

}
