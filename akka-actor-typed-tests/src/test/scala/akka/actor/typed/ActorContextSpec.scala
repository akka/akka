/**
 * Copyright (C) 2017-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors._
import akka.actor.typed.scaladsl.{ Behaviors, AskPattern }
import akka.actor.{ ActorInitializationException, DeadLetterSuppression, InvalidMessageException }
import akka.testkit.AkkaSpec
import akka.testkit.TestEvent.Mute
import com.typesafe.config.ConfigFactory
import org.scalactic.CanEqual

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.existentials
import scala.reflect.ClassTag
import scala.util.control.{ NoStackTrace, NonFatal }

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
    Behaviors.immutable[Command] {
      (ctx, message) ⇒
        message match {
          case ReceiveTimeout ⇒
            monitor ! GotReceiveTimeout
            Behaviors.same
          case Ping(replyTo) ⇒
            replyTo ! Pong1
            Behaviors.same
          case Miss(replyTo) ⇒
            replyTo ! Missed
            Behaviors.unhandled
          case Renew(replyTo) ⇒
            replyTo ! Renewed
            subject(monitor, ignorePostStop)
          case Throw(ex) ⇒
            throw ex
          case MkChild(name, mon, replyTo) ⇒
            val child = name match {
              case None    ⇒ ctx.spawnAnonymous(Behaviors.supervise(subject(mon, ignorePostStop)).onFailure(SupervisorStrategy.restart))
              case Some(n) ⇒ ctx.spawn(Behaviors.supervise(subject(mon, ignorePostStop)).onFailure(SupervisorStrategy.restart), n)
            }
            replyTo ! Created(child)
            Behaviors.same
          case SetTimeout(d, replyTo) ⇒
            d match {
              case f: FiniteDuration ⇒ ctx.setReceiveTimeout(f, ReceiveTimeout)
              case _                 ⇒ ctx.cancelReceiveTimeout()
            }
            replyTo ! TimeoutSet
            Behaviors.same
          case Schedule(delay, target, msg, replyTo) ⇒
            replyTo ! Scheduled
            ctx.schedule(delay, target, msg)
            Behaviors.same
          case Stop ⇒
            Behaviors.stopped
          case Kill(ref, replyTo) ⇒
            if (ctx.stop(ref)) replyTo ! Killed
            else replyTo ! NotKilled
            Behaviors.same
          case Watch(ref, replyTo) ⇒
            ctx.watch(ref)
            replyTo ! Watched
            Behaviors.same
          case Unwatch(ref, replyTo) ⇒
            ctx.unwatch(ref)
            replyTo ! Unwatched
            Behaviors.same
          case GetInfo(replyTo) ⇒
            replyTo ! Info(ctx.self, ctx.system)
            Behaviors.same
          case GetChild(name, replyTo) ⇒
            replyTo ! Child(ctx.child(name))
            Behaviors.same
          case GetChildren(replyTo) ⇒
            replyTo ! Children(ctx.children.toSet)
            Behaviors.same
          case BecomeInert(replyTo) ⇒
            replyTo ! BecameInert
            Behaviors.immutable {
              case (_, Ping(replyTo)) ⇒
                replyTo ! Pong2
                Behaviors.same
              case (_, Throw(ex)) ⇒
                throw ex
              case _ ⇒ Behaviors.unhandled
            }
          case BecomeCareless(replyTo) ⇒
            replyTo ! BecameCareless
            Behaviors.immutable[Command] {
              case (_, _) ⇒ Behaviors.unhandled
            } onSignal {
              case (_, PostStop) if ignorePostStop ⇒ Behaviors.same // ignore PostStop here
              case (_, Terminated(_))              ⇒ Behaviors.unhandled
              case (_, sig) ⇒
                monitor ! GotSignal(sig)
                Behaviors.same
            }
          case GetAdapter(replyTo, name) ⇒
            replyTo ! Adapter(ctx.spawnMessageAdapter(identity, name))
            Behaviors.same
        }
    } onSignal {
      case (_, PostStop) if ignorePostStop ⇒ Behaviors.same // ignore PostStop here
      case (ctx, signal)                   ⇒ monitor ! GotSignal(signal); Behaviors.same
    }

  def oldSubject(monitor: ActorRef[Monitor], ignorePostStop: Boolean): Behavior[Command] = {
    Behaviors.immutable[Command] {
      case (ctx, message) ⇒ message match {
        case ReceiveTimeout ⇒
          monitor ! GotReceiveTimeout
          Behaviors.same
        case Ping(replyTo) ⇒
          replyTo ! Pong1
          Behaviors.same
        case Miss(replyTo) ⇒
          replyTo ! Missed
          Behaviors.unhandled
        case Renew(replyTo) ⇒
          replyTo ! Renewed
          subject(monitor, ignorePostStop)
        case Throw(ex) ⇒
          throw ex
        case MkChild(name, mon, replyTo) ⇒
          val child = name match {
            case None    ⇒ ctx.spawnAnonymous(Behaviors.supervise(subject(mon, ignorePostStop)).onFailure[Throwable](SupervisorStrategy.restart))
            case Some(n) ⇒ ctx.spawn(Behaviors.supervise(subject(mon, ignorePostStop)).onFailure[Throwable](SupervisorStrategy.restart), n)
          }
          replyTo ! Created(child)
          Behaviors.same
        case SetTimeout(d, replyTo) ⇒
          d match {
            case f: FiniteDuration ⇒ ctx.setReceiveTimeout(f, ReceiveTimeout)
            case _                 ⇒ ctx.cancelReceiveTimeout()
          }
          replyTo ! TimeoutSet
          Behaviors.same
        case Schedule(delay, target, msg, replyTo) ⇒
          replyTo ! Scheduled
          ctx.schedule(delay, target, msg)
          Behaviors.same
        case Stop ⇒
          Behaviors.stopped
        case Kill(ref, replyTo) ⇒
          if (ctx.stop(ref)) replyTo ! Killed
          else replyTo ! NotKilled
          Behaviors.same
        case Watch(ref, replyTo) ⇒
          ctx.watch(ref)
          replyTo ! Watched
          Behaviors.same
        case Unwatch(ref, replyTo) ⇒
          ctx.unwatch(ref)
          replyTo ! Unwatched
          Behaviors.same
        case GetInfo(replyTo) ⇒
          replyTo ! Info(ctx.self, ctx.system)
          Behaviors.same
        case GetChild(name, replyTo) ⇒
          replyTo ! Child(ctx.child(name))
          Behaviors.same
        case GetChildren(replyTo) ⇒
          replyTo ! Children(ctx.children.toSet)
          Behaviors.same
        case BecomeInert(replyTo) ⇒
          replyTo ! BecameInert
          Behaviors.immutable[Command] {
            case (_, Ping(r)) ⇒
              r ! Pong2
              Behaviors.same
            case (_, Throw(ex)) ⇒
              throw ex
            case _ ⇒ Behaviors.same
          }
        case BecomeCareless(replyTo) ⇒
          replyTo ! BecameCareless
          Behaviors.immutable[Command] {
            case _ ⇒ Behaviors.unhandled
          } onSignal {
            case (_, PostStop) if ignorePostStop ⇒ Behaviors.same // ignore PostStop here
            case (_, Terminated(_))              ⇒ Behaviors.unhandled
            case (_, sig) ⇒
              monitor ! GotSignal(sig)
              Behaviors.same
          }
        case GetAdapter(replyTo, name) ⇒
          replyTo ! Adapter(ctx.spawnMessageAdapter(identity, name))
          Behaviors.same
      }
    } onSignal {
      case (_, PostStop) if ignorePostStop ⇒ Behaviors.same // ignore PostStop here
      case (_, signal) ⇒
        monitor ! GotSignal(signal)
        Behaviors.same
    }
  }

  sealed abstract class Start
  case object Start extends Start

  sealed trait GuardianCommand
  case class RunTest[T](name: String, behavior: Behavior[T], replyTo: ActorRef[Status], timeout: FiniteDuration) extends GuardianCommand
  case class Terminate(reply: ActorRef[Status]) extends GuardianCommand
  case class Create[T](behavior: Behavior[T], name: String)(val replyTo: ActorRef[ActorRef[T]]) extends GuardianCommand

  sealed trait Status
  case object Success extends Status
  case class Failed(thr: Throwable) extends Status
  case object Timedout extends Status

  class SimulatedException(message: String) extends RuntimeException(message) with NoStackTrace

  def guardian(outstanding: Map[ActorRef[_], ActorRef[Status]] = Map.empty): Behavior[GuardianCommand] =
    Behaviors.immutable[GuardianCommand] {
      case (ctx, r: RunTest[t]) ⇒
        val test = ctx.spawn(r.behavior, r.name)
        ctx.schedule(r.timeout, r.replyTo, Timedout)
        ctx.watch(test)
        guardian(outstanding + ((test, r.replyTo)))
      case (_, Terminate(reply)) ⇒
        reply ! Success
        stopped
      case (ctx, c: Create[t]) ⇒
        c.replyTo ! ctx.spawn(c.behavior, c.name)
        same
    } onSignal {
      case (ctx, t @ Terminated(test)) ⇒
        outstanding get test match {
          case Some(reply) ⇒
            if (t.failure eq null) reply ! Success
            else reply ! Failed(t.failure)
            guardian(outstanding - test)
          case None ⇒ same
        }
      case _ ⇒ same
    }
}

abstract class ActorContextSpec extends TypedAkkaSpec {
  import ActorContextSpec._

  val config = ConfigFactory.parseString(
    """|akka {
     |  loglevel = WARNING
     |  actor.debug {
     |    lifecycle = off
     |    autoreceive = off
     |  }
     |  typed.loggers = ["akka.testkit.typed.TestEventListener"]
     |}""".stripMargin)

  implicit lazy val system: ActorSystem[GuardianCommand] =
    ActorSystem(guardian(), AkkaSpec.getCallerName(classOf[ActorContextSpec]), config = Some(config withFallback AkkaSpec.testConf))

  val expectTimeout = 3.seconds
  import AskPattern._

  implicit def scheduler = system.scheduler

  lazy val blackhole = await(system ? Create(immutable[Any] { case _ ⇒ same }, "blackhole"))

  override def afterAll(): Unit = {
    Await.result(system.terminate, timeout.duration)
  }

  // TODO remove after basing on ScalaTest 3 with async support
  import akka.testkit._

  def await[T](f: Future[T]): T = Await.result(f, timeout.duration * 1.1)

  /**
   * Run an Actor-based test. The test procedure is most conveniently
   * formulated using the [[StepWise]] behavior type.
   */
  def runTest[T: ClassTag](name: String)(behavior: Behavior[T])(implicit system: ActorSystem[GuardianCommand]): Future[Status] =
    system ? (RunTest(name, behavior, _, timeout.duration))

  // TODO remove after basing on ScalaTest 3 with async support
  def sync(f: Future[Status])(implicit system: ActorSystem[GuardianCommand]): Unit = {
    def unwrap(ex: Throwable): Throwable = ex match {
      case ActorInitializationException(_, _, ex) ⇒ ex
      case other                                  ⇒ other
    }

    try await(f) match {
      case Success ⇒ ()
      case Failed(ex) ⇒
        unwrap(ex) match {
          case ex2: SimulatedException ⇒
            throw ex2
          case _ ⇒
            println(system.printTree)
            throw unwrap(ex)
        }
      case Timedout ⇒
        println(system.printTree)
        fail("test timed out")
    } catch {
      case ex: SimulatedException ⇒
        throw ex
      case NonFatal(ex) ⇒
        println(system.printTree)
        throw ex
    }
  }

  def muteExpectedException[T <: Exception: ClassTag](
    message:     String = null,
    source:      String = null,
    start:       String = "",
    pattern:     String = null,
    occurrences: Int    = Int.MaxValue)(implicit system: ActorSystem[GuardianCommand]): EventFilter = {
    val filter = EventFilter(message, source, start, pattern, occurrences)
    system.eventStream.publish(Mute(filter))
    filter
  }

  // for ScalaTest === compare of Class objects
  implicit def classEqualityConstraint[A, B]: CanEqual[Class[A], Class[B]] =
    new CanEqual[Class[A], Class[B]] {
      def areEqual(a: Class[A], b: Class[B]) = a == b
    }

  implicit def setEqualityConstraint[A, T <: Set[_ <: A]]: CanEqual[Set[A], T] =
    new CanEqual[Set[A], T] {
      def areEqual(a: Set[A], b: T) = a == b
    }

  /**
   * The name for the set of tests to be instantiated, used for keeping the test case actors’ names unique.
   */
  def suite: String

  /**
   * The behavior against which to run all the tests.
   */
  def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command]

  private def mySuite: String = suite + "Adapted"

  def setup(name: String, wrapper: Option[Behavior[Command] ⇒ Behavior[Command]] = None, ignorePostStop: Boolean = true)(
    proc: (scaladsl.ActorContext[Event], StepWise.Steps[Event, ActorRef[Command]]) ⇒ StepWise.Steps[Event, _]): Future[Status] =
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

  "An ActorContext" must {
    "canonicalize behaviors" in {
      sync(setup("ctx00") { (ctx, startWith) ⇒
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
    }

    "correctly wire the lifecycle hooks" in {
      sync(setup("ctx01", Some(b ⇒ Behaviors.supervise(b).onFailure[Throwable](SupervisorStrategy.restart)), ignorePostStop = false) { (ctx, startWith) ⇒
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
    }

    "signal PostStop after voluntary termination" in {
      sync(setup("ctx02", ignorePostStop = false) { (ctx, startWith) ⇒
        startWith.keep { subj ⇒
          stop(subj)
        }.expectMessage(expectTimeout) {
          case (msg, _) ⇒
            msg should ===(GotSignal(PostStop))
        }
      })
    }

    "restart and stop a child actor" in {
      sync(setup("ctx03") { (ctx, startWith) ⇒
        val self = ctx.self
        val ex = new Exception("KABOOM2")
        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self) {
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
    }

    "stop a child actor" in {
      sync(setup("ctx04") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.mkChild(Some("A"), ctx.spawnMessageAdapter(ChildEvent), self, inert = true) {
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
    }

    "reset behavior upon Restart" in {
      sync(setup("ctx05", Some(Behaviors.supervise(_).onFailure(SupervisorStrategy.restart))) { (ctx, startWith) ⇒
        val self = ctx.self
        val ex = new Exception("KABOOM05")
        startWith
          .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
          .stimulate(_ ! Ping(self), _ ⇒ Pong2) { subj ⇒
            muteExpectedException[Exception]("KABOOM05")
            subj ! Throw(ex)
            subj
          }
          .stimulate(_ ! Ping(self), _ ⇒ Pong1)
      })
    }

    "not reset behavior upon Resume" in {
      sync(setup(
        "ctx06",
        Some(b ⇒ Behaviors.supervise(b).onFailure(SupervisorStrategy.resume))) { (ctx, startWith) ⇒
          val self = ctx.self
          val ex = new Exception("KABOOM06")
          startWith
            .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
            .stimulate(_ ! Ping(self), _ ⇒ Pong2).keep { subj ⇒
              muteExpectedException[Exception]("KABOOM06", occurrences = 1)
              subj ! Throw(ex)
            }.stimulate(_ ! Ping(self), _ ⇒ Pong2)
        })
    }

    "stop upon Stop" in {
      sync(setup("ctx07", ignorePostStop = false) { (ctx, startWith) ⇒
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
    }

    "not stop non-child actor" in {
      sync(setup("ctx08") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.mkChild(Some("A"), ctx.spawnMessageAdapter(ChildEvent), self) {
          case (subj, child) ⇒
            val other = ctx.spawn(behavior(ctx, ignorePostStop = true), "A")
            subj ! Kill(other, ctx.self)
            child
        }.expectMessageKeep(expectTimeout) { (msg, _) ⇒
          msg should ===(NotKilled)
        }.stimulate(_ ! Ping(self), _ ⇒ Pong1)
      })
    }

    "watch a child actor before its termination" in {
      sync(setup("ctx10") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self) {
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
    }

    "watch a child actor after its termination" in {
      sync(setup("ctx11") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self).keep {
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
    }

    "unwatch a child actor before its termination" in {
      sync(setup("ctx12") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self).keep {
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
    }

    "terminate upon not handling Terminated" in {
      sync(setup("ctx13", ignorePostStop = false) { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.mkChild(None, ctx.spawnMessageAdapter(ChildEvent), self).keep {
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
    }

    "return the right context info" in {
      sync(setup("ctx20") { (ctx, startWith) ⇒
        startWith.keep(_ ! GetInfo(ctx.self))
          .expectMessage(expectTimeout) {
            case (msg: Info, subj) ⇒
              msg.self should ===(subj)
              msg.system should ===(system)
            case (other, _) ⇒
              fail(s"$other was not an Info(...)")
          }
      })
    }

    "return right info about children" in {
      sync(setup("ctx21") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith
          .mkChild(Some("B"), ctx.spawnMessageAdapter(ChildEvent), self)
          .stimulate(_._1 ! GetChild("A", self), _ ⇒ Child(None))
          .stimulate(_._1 ! GetChild("B", self), x ⇒ Child(Some(x._2)))
          .stimulate(_._1 ! GetChildren(self), x ⇒ Children(Set(x._2)))
      })
    }

    "set small receive timeout" in {
      sync(setup("ctx30") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith
          .stimulate(_ ! SetTimeout(1.nano, self), _ ⇒ TimeoutSet)
          .expectMessage(expectTimeout) { (msg, _) ⇒
            msg should ===(GotReceiveTimeout)
          }
      })
    }

    "set large receive timeout" in {
      sync(setup("ctx31") { (ctx, startWith) ⇒
        val self = ctx.self
        startWith
          .stimulate(_ ! SetTimeout(1.minute, self), _ ⇒ TimeoutSet)
          .stimulate(_ ⇒ ctx.schedule(1.second, self, Pong2), _ ⇒ Pong2)
          .stimulate(_ ! Ping(self), _ ⇒ Pong1)

      })
    }

    "schedule a message" in {
      sync(setup("ctx32") { (ctx, startWith) ⇒
        startWith(_ ! Schedule(1.nano, ctx.self, Pong2, ctx.self))
          .expectMultipleMessages(expectTimeout, 2) { (msgs, _) ⇒
            msgs should ===(Scheduled :: Pong2 :: Nil)
          }
      })
    }

    "create a working adapter" in {
      sync(setup("ctx40", ignorePostStop = false) { (ctx, startWith) ⇒
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
    }

    "create a named adapter" in {
      sync(setup("ctx41") { (ctx, startWith) ⇒
        startWith.keep { subj ⇒
          subj ! GetAdapter(ctx.self, "named")
        }.expectMessage(expectTimeout) { (msg, subj) ⇒
          val Adapter(adapter) = msg
          adapter.path.name should include("named")
        }
      })
    }

    "not allow null messages" in {
      sync(setup("ctx42") { (ctx, startWith) ⇒
        startWith.keep { subj ⇒
          intercept[InvalidMessageException] {
            subj ! null
          }
        }
      })
    }
  }
}

import akka.actor.typed.ActorContextSpec._

class NormalActorContextSpec extends ActorContextSpec {
  override def suite = "normal"
  override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
    subject(ctx.self, ignorePostStop)
}

class WidenedActorContextSpec extends ActorContextSpec {

  import Behaviors._

  override def suite = "widened"
  override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
    subject(ctx.self, ignorePostStop).widen { case x ⇒ x }
}

class DeferredActorContextSpec extends ActorContextSpec {
  override def suite = "deferred"
  override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
    Behaviors.deferred(_ ⇒ subject(ctx.self, ignorePostStop))
}

class NestedDeferredActorContextSpec extends ActorContextSpec {
  override def suite = "nexted-deferred"
  override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
    Behaviors.deferred(_ ⇒ Behaviors.deferred(_ ⇒ subject(ctx.self, ignorePostStop)))
}

class TapActorContextSpec extends ActorContextSpec {
  override def suite = "tap"
  override def behavior(ctx: scaladsl.ActorContext[Event], ignorePostStop: Boolean): Behavior[Command] =
    Behaviors.tap((_, _) ⇒ (), (_, _) ⇒ (), subject(ctx.self, ignorePostStop))
}
