package akka.typed

import scala.concurrent.duration._
import scala.concurrent.Future
import org.scalautils.ConversionCheckedTripleEquals
import com.typesafe.config.ConfigFactory
import akka.actor.DeadLetterSuppression

object ActorContextSpec {
  import ScalaDSL._

  sealed trait Command
  sealed trait Event

  final case class GotSignal(signal: Signal) extends Event with DeadLetterSuppression

  final case class Ping(replyTo: ActorRef[Pong]) extends Command
  sealed trait Pong extends Event
  case object Pong1 extends Pong
  case object Pong2 extends Pong

  final case class Miss(replyTo: ActorRef[Missed.type]) extends Command
  case object Missed extends Event

  final case class Renew(replyTo: ActorRef[Renewed.type]) extends Command
  case object Renewed extends Event

  final case class Throw(ex: Exception) extends Command

  final case class MkChild(name: Option[String], monitor: ActorRef[GotSignal], replyTo: ActorRef[Created]) extends Command
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
  final case class Info(self: ActorRef[Command], props: Props[Command], system: ActorSystem[Nothing]) extends Event

  final case class GetChild(name: String, replyTo: ActorRef[Child]) extends Command
  final case class Child(c: Option[ActorRef[Nothing]]) extends Event

  final case class GetChildren(replyTo: ActorRef[Children]) extends Command
  final case class Children(c: Set[ActorRef[Nothing]]) extends Event

  final case class ChildEvent(event: Event) extends Event

  final case class BecomeInert(replyTo: ActorRef[BecameInert.type]) extends Command
  case object BecameInert extends Event

  final case class BecomeCareless(replyTo: ActorRef[BecameCareless.type]) extends Command
  case object BecameCareless extends Event

  final case class GetAdapter(replyTo: ActorRef[Adapter]) extends Command
  final case class Adapter(a: ActorRef[Command]) extends Event

  def subject(monitor: ActorRef[GotSignal]): Behavior[Command] =
    FullTotal {
      case Sig(ctx, signal) ⇒
        monitor ! GotSignal(signal)
        signal match {
          case f: Failed ⇒ f.decide(Failed.Restart)
          case _         ⇒
        }
        Same
      case Msg(ctx, message) ⇒ message match {
        case Ping(replyTo) ⇒
          replyTo ! Pong1
          Same
        case Miss(replyTo) ⇒
          replyTo ! Missed
          Unhandled
        case Renew(replyTo) ⇒
          replyTo ! Renewed
          subject(monitor)
        case Throw(ex) ⇒
          throw ex
        case MkChild(name, mon, replyTo) ⇒
          val child = name match {
            case None    ⇒ ctx.spawnAnonymous(Props(subject(mon)))
            case Some(n) ⇒ ctx.spawn(Props(subject(mon)), n)
          }
          replyTo ! Created(child)
          Same
        case SetTimeout(d, replyTo) ⇒
          ctx.setReceiveTimeout(d)
          replyTo ! TimeoutSet
          Same
        case Schedule(delay, target, msg, replyTo) ⇒
          replyTo ! Scheduled
          ctx.schedule(delay, target, msg)
          Same
        case Stop ⇒ Stopped
        case Kill(ref, replyTo) ⇒
          if (ctx.stop(ref)) replyTo ! Killed
          else replyTo ! NotKilled
          Same
        case Watch(ref, replyTo) ⇒
          ctx.watch[Nothing](ref)
          replyTo ! Watched
          Same
        case Unwatch(ref, replyTo) ⇒
          ctx.unwatch[Nothing](ref)
          replyTo ! Unwatched
          Same
        case GetInfo(replyTo) ⇒
          replyTo ! Info(ctx.self, ctx.props, ctx.system)
          Same
        case GetChild(name, replyTo) ⇒
          replyTo ! Child(ctx.child(name))
          Same
        case GetChildren(replyTo) ⇒
          replyTo ! Children(ctx.children.toSet)
          Same
        case BecomeInert(replyTo) ⇒
          replyTo ! BecameInert
          Full {
            case Msg(_, Ping(replyTo)) ⇒
              replyTo ! Pong2
              Same
            case Msg(_, Throw(ex)) ⇒
              throw ex
            case _ ⇒ Same
          }
        case BecomeCareless(replyTo) ⇒
          replyTo ! BecameCareless
          Full {
            case Sig(_, Terminated(_)) ⇒ Unhandled
            case Sig(_, sig) ⇒
              monitor ! GotSignal(sig)
              Same
          }
        case GetAdapter(replyTo) ⇒
          replyTo ! Adapter(ctx.spawnAdapter(identity))
          Same
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
     |}""".stripMargin)) {
  import ActorContextSpec._
  import ScalaDSL._

  val expectTimeout = 3.seconds

  trait Tests {
    /**
     * The name for the set of tests to be instantiated, used for keeping the test case actors’ names unique.
     */
    def suite: String

    /**
     * The behavior against which to run all the tests.
     */
    def behavior(ctx: ActorContext[Event]): Behavior[Command]

    def setup(name: String)(proc: (ActorContext[Event], StepWise.Steps[Event, ActorRef[Command]]) ⇒ StepWise.Steps[Event, _]): Future[TypedSpec.Status] =
      runTest(s"$suite-$name")(StepWise[Event] { (ctx, startWith) ⇒
        val steps =
          startWith.withKeepTraces(true)(ctx.spawn(Props(behavior(ctx)), "subject"))
            .expectMessage(expectTimeout) { (msg, ref) ⇒
              msg should ===(GotSignal(PreStart))
              ref
            }
        proc(ctx, steps)
      })

    private implicit class MkC(val startWith: StepWise.Steps[Event, ActorRef[Command]]) {
      /**
       * Ask the subject to create a child actor, setting its behavior to “inert” if requested.
       * The latter is very useful in order to avoid disturbances with GotSignal(PostStop) in
       * test procedures that stop this child.
       */
      def mkChild(name: Option[String],
                  monitor: ActorRef[Event],
                  self: ActorRef[Event],
                  inert: Boolean = false): StepWise.Steps[Event, (ActorRef[Command], ActorRef[Command])] = {
        val s =
          startWith.keep { subj ⇒
            subj ! MkChild(name, monitor, self)
          }.expectMultipleMessages(expectTimeout, 2) { (msgs, subj) ⇒
            val child = msgs match {
              case Created(child) :: ChildEvent(GotSignal(PreStart)) :: Nil ⇒ child
              case ChildEvent(GotSignal(PreStart)) :: Created(child) :: Nil ⇒ child
            }
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

    def `01 must correctly wire the lifecycle hooks`(): Unit = sync(setup("ctx01") { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM1")
      startWith { subj ⇒
        val log = muteExpectedException[Exception]("KABOOM1", occurrences = 1)
        subj ! Throw(ex)
        (subj, log)
      }.expectFailureKeep(expectTimeout) {
        case (f, (subj, _)) ⇒
          f.cause should ===(ex)
          f.child should ===(subj)
          Failed.Restart
      }.expectMessage(expectTimeout) {
        case (msg, (subj, log)) ⇒
          msg should ===(GotSignal(PreRestart(ex)))
          log.assertDone(expectTimeout)
          subj
      }.expectMessage(expectTimeout) { (msg, subj) ⇒
        msg should ===(GotSignal(PostRestart(ex)))
        ctx.stop(subj)
      }.expectMessage(expectTimeout) { (msg, _) ⇒
        msg should ===(GotSignal(PostStop))
      }
    })

    def `02 must not signal PostStop after voluntary termination`(): Unit = sync(setup("ctx02") { (ctx, startWith) ⇒
      startWith.keep { subj ⇒
        ctx.watch(subj)
        stop(subj)
      }.expectTermination(expectTimeout) { (t, subj) ⇒
        t.ref should ===(subj)
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
      }.expectMultipleMessages(expectTimeout, 3) {
        case (msgs, (subj, child, log)) ⇒
          msgs should ===(
            GotSignal(Failed(`ex`, `child`)) ::
              ChildEvent(GotSignal(PreRestart(`ex`))) ::
              ChildEvent(GotSignal(PostRestart(`ex`))) :: Nil)
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

    def `05 must reset behavior upon Restart`(): Unit = sync(setup("ctx05") { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM05")
      startWith
        .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
        .stimulate(_ ! Ping(self), _ ⇒ Pong2) { subj ⇒
          val log = muteExpectedException[Exception]("KABOOM05")
          subj ! Throw(ex)
          (subj, log)
        }.expectFailureKeep(expectTimeout) {
          case (f, (subj, log)) ⇒
            f.child should ===(subj)
            f.cause should ===(ex)
            Failed.Restart
        }.expectMessage(expectTimeout) {
          case (msg, (subj, log)) ⇒
            msg should ===(GotSignal(PostRestart(ex)))
            log.assertDone(expectTimeout)
            subj
        }.stimulate(_ ! Ping(self), _ ⇒ Pong1)
    })

    def `06 must not reset behavior upon Resume`(): Unit = sync(setup("ctx06") { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM05")
      startWith
        .stimulate(_ ! BecomeInert(self), _ ⇒ BecameInert)
        .stimulate(_ ! Ping(self), _ ⇒ Pong2).keep { subj ⇒
          subj ! Throw(ex)
        }.expectFailureKeep(expectTimeout) { (f, subj) ⇒
          f.child should ===(subj)
          f.cause should ===(ex)
          Failed.Resume
        }.stimulate(_ ! Ping(self), _ ⇒ Pong2)
    })

    def `07 must stop upon Stop`(): Unit = sync(setup("ctx07") { (ctx, startWith) ⇒
      val self = ctx.self
      val ex = new Exception("KABOOM05")
      startWith
        .stimulate(_ ! Ping(self), _ ⇒ Pong1).keep { subj ⇒
          subj ! Throw(ex)
          ctx.watch(subj)
        }.expectFailureKeep(expectTimeout) { (f, subj) ⇒
          f.child should ===(subj)
          f.cause should ===(ex)
          Failed.Stop
        }.expectMessageKeep(expectTimeout) { (msg, _) ⇒
          msg should ===(GotSignal(PostStop))
        }.expectTermination(expectTimeout) { (t, subj) ⇒
          t.ref should ===(subj)
        }
    })

    def `08 must not stop non-child actor`(): Unit = sync(setup("ctx08") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(Some("A"), ctx.spawnAdapter(ChildEvent), self) { pair ⇒
        (pair._1, pair._2, ctx.spawn(Props(behavior(ctx)), "A"))
      }.expectMessage(expectTimeout) {
        case (msg, (subj, child, other)) ⇒
          msg should ===(GotSignal(PreStart))
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
        msg should ===(GotSignal(Terminated(child)))
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
          t should ===(Terminated(child))
          subj ! Watch(child, blackhole)
          child
      }.expectMessage(expectTimeout) { (msg, child) ⇒
        msg should ===(GotSignal(Terminated(child)))
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
        t should ===(Terminated(child))
      }
    })

    def `13 must terminate upon not handling Terminated`(): Unit = sync(setup("ctx13") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith.mkChild(None, ctx.spawnAdapter(ChildEvent), self).keep {
        case (subj, child) ⇒
          subj ! Watch(child, self)
      }.expectMessageKeep(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(Watched)
          subj ! BecomeCareless(self)
      }.expectMessageKeep(expectTimeout) {
        case (msg, (subj, child)) ⇒
          msg should ===(BecameCareless)
          child ! Stop
      }.expectFailureKeep(expectTimeout) {
        case (f, (subj, child)) ⇒
          f.child should ===(subj)
          Failed.Stop
      }.expectMessage(expectTimeout) {
        case (msg, (subj, child)) ⇒
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
          msg should ===(GotSignal(ReceiveTimeout))
        }
    })

    def `31 must set large receive timeout`(): Unit = sync(setup("ctx31") { (ctx, startWith) ⇒
      val self = ctx.self
      startWith
        .stimulate(_ ! SetTimeout(1.minute, self), _ ⇒ TimeoutSet)
        .stimulate(_ ⇒ ctx.schedule(1.second, self, Pong2), _ ⇒ Pong2, 1.5.seconds)
        .stimulate(_ ! Ping(self), _ ⇒ Pong1)
    })

    def `32 must schedule a message`(): Unit = sync(setup("ctx32") { (ctx, startWith) ⇒
      startWith(_ ! Schedule(1.nano, ctx.self, Pong2, ctx.self))
        .expectMultipleMessages(expectTimeout, 2) { (msgs, _) ⇒
          msgs should ===(Scheduled :: Pong2 :: Nil)
        }
    })

    def `40 must create a working adapter`(): Unit = sync(setup("ctx40") { (ctx, startWith) ⇒
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
      }.expectMessageKeep(expectTimeout) { (msg, _) ⇒
        msg should ===(GotSignal(PostStop))
      }.expectTermination(expectTimeout) { (t, adapter) ⇒
        t.ref should ===(adapter)
      }
    })
  }

  object `An ActorContext` extends Tests {
    override def suite = "basic"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] = subject(ctx.self)
  }

  object `An ActorContext with widened Behavior` extends Tests {
    override def suite = "widened"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] = subject(ctx.self).widen { case x ⇒ x }
  }

  object `An ActorContext with SynchronousSelf` extends Tests {
    override def suite = "synchronous"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] = SynchronousSelf(self ⇒ subject(ctx.self))
  }

  object `An ActorContext with non-matching Tap` extends Tests {
    override def suite = "TapNonMatch"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] = Tap({ case null ⇒ }, subject(ctx.self))
  }

  object `An ActorContext with matching Tap` extends Tests {
    override def suite = "TapMatch"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] = Tap({ case _ ⇒ }, subject(ctx.self))
  }

  private val stoppingBehavior = Full[Command] { case Msg(_, Stop) ⇒ Stopped }

  object `An ActorContext with And (left)` extends Tests {
    override def suite = "and"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] =
      And(subject(ctx.self), stoppingBehavior)
  }

  object `An ActorContext with And (right)` extends Tests {
    override def suite = "and"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] =
      And(stoppingBehavior, subject(ctx.self))
  }

  object `An ActorContext with Or (left)` extends Tests {
    override def suite = "basic"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] =
      Or(subject(ctx.self), stoppingBehavior)
    override def stop(ref: ActorRef[Command]) = {
      ref ! Stop
      ref ! Stop
    }
  }

  object `An ActorContext with Or (right)` extends Tests {
    override def suite = "basic"
    override def behavior(ctx: ActorContext[Event]): Behavior[Command] =
      Or(stoppingBehavior, subject(ctx.self))
    override def stop(ref: ActorRef[Command]) = {
      ref ! Stop
      ref ! Stop
    }
  }

}
