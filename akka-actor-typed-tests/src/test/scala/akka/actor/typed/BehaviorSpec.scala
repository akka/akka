/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.{ Actor ⇒ SActor }
import akka.actor.typed.javadsl.{ Actor ⇒ JActor, ActorContext ⇒ JActorContext }
import akka.japi.function.{ Function ⇒ F1e, Function2 ⇒ F2, Procedure2 ⇒ P2 }
import akka.japi.pf.{ FI, PFBuilder }
import java.util.function.{ Function ⇒ F1 }

import akka.Done
import akka.typed.testkit.{ EffectfulActorContext, Inbox }

object BehaviorSpec {
  sealed trait Command {
    def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Nil
  }
  case object GetSelf extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Self(ctx.asScala.self) :: Nil
  }
  // Behavior under test must return Unhandled
  case object Miss extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Missed :: Nil
  }
  // Behavior under test must return same
  case object Ignore extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Ignored :: Nil
  }
  case object Ping extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Pong :: Nil
  }
  case object Swap extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Swapped :: Nil
  }
  case class GetState()(s: State) extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = s :: Nil
  }
  case class AuxPing(id: Int) extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Pong :: Nil
  }
  case object Stop extends Command

  sealed trait Event
  case class GotSignal(signal: Signal) extends Event
  case class Self(self: ActorRef[Command]) extends Event
  case object Missed extends Event
  case object Ignored extends Event
  case object Pong extends Event
  case object Swapped extends Event

  sealed trait State extends Event {
    def next: State
  }
  val StateA: State = new State {
    override def toString = "StateA"
    override def next = StateB
  }
  val StateB: State = new State {
    override def toString = "StateB"
    override def next = StateA
  }

  trait Common extends TypedSpec {
    type Aux >: Null <: AnyRef
    def system: ActorSystem[TypedSpec.Command]
    def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux)
    def checkAux(signal: Signal, aux: Aux): Unit = ()
    def checkAux(command: Command, aux: Aux): Unit = ()

    case class Init(behv: Behavior[Command], inbox: Inbox[Event], aux: Aux) {
      def mkCtx(): Setup = {
        val ctx = new EffectfulActorContext("ctx", behv, 1000, system)
        val msgs = inbox.receiveAll()
        Setup(ctx, inbox, aux)
      }
    }
    case class Setup(ctx: EffectfulActorContext[Command], inbox: Inbox[Event], aux: Aux)

    def init(): Init = {
      val inbox = Inbox[Event]("evt")
      val (behv, aux) = behavior(inbox.ref)
      Init(behv, inbox, aux)
    }

    def init(factory: ActorRef[Event] ⇒ (Behavior[Command], Aux)): Init = {
      val inbox = Inbox[Event]("evt")
      val (behv, aux) = factory(inbox.ref)
      Init(behv, inbox, aux)
    }

    def mkCtx(requirePreStart: Boolean = false): Setup =
      init().mkCtx()

    implicit class Check(val setup: Setup) {
      def check(signal: Signal): Setup = {
        setup.ctx.signal(signal)
        setup.inbox.receiveAll() should ===(GotSignal(signal) :: Nil)
        checkAux(signal, setup.aux)
        setup
      }
      def check(command: Command): Setup = {
        setup.ctx.run(command)
        setup.inbox.receiveAll() should ===(command.expectedResponse(setup.ctx))
        checkAux(command, setup.aux)
        setup
      }
      def check2(command: Command): Setup = {
        setup.ctx.run(command)
        val expected = command.expectedResponse(setup.ctx)
        setup.inbox.receiveAll() should ===(expected ++ expected)
        checkAux(command, setup.aux)
        setup
      }
    }

    val ex = new Exception("mine!")
  }

  trait Siphon extends Common {
    override type Aux = Inbox[Command]

    override def checkAux(command: Command, aux: Aux): Unit = {
      aux.receiveAll() should ===(command :: Nil)
    }
  }

  trait SignalSiphon extends Common {
    override type Aux = Inbox[Either[Signal, Command]]

    override def checkAux(command: Command, aux: Aux): Unit = {
      aux.receiveAll() should ===(Right(command) :: Nil)
    }

    override def checkAux(signal: Signal, aux: Aux): Unit = {
      aux.receiveAll() should ===(Left(signal) :: Nil)
    }
  }

  def mkFull(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] = {
    SActor.immutable[Command] {
      case (ctx, GetSelf) ⇒
        monitor ! Self(ctx.self)
        SActor.same
      case (ctx, Miss) ⇒
        monitor ! Missed
        SActor.unhandled
      case (ctx, Ignore) ⇒
        monitor ! Ignored
        SActor.same
      case (ctx, Ping) ⇒
        monitor ! Pong
        mkFull(monitor, state)
      case (ctx, Swap) ⇒
        monitor ! Swapped
        mkFull(monitor, state.next)
      case (ctx, GetState()) ⇒
        monitor ! state
        SActor.same
      case (ctx, Stop) ⇒ SActor.stopped
      case (_, _)      ⇒ SActor.unhandled
    } onSignal {
      case (ctx, signal) ⇒
        monitor ! GotSignal(signal)
        SActor.same
    }
  }
  /*
 * function converters for Java, to ease the pain on Scala 2.11
 */
  def fs(f: (JActorContext[Command], Signal) ⇒ Behavior[Command]) =
    new F2[JActorContext[Command], Signal, Behavior[Command]] {
      override def apply(ctx: JActorContext[Command], sig: Signal) = f(ctx, sig)
    }
  def fc(f: (JActorContext[Command], Command) ⇒ Behavior[Command]) =
    new F2[JActorContext[Command], Command, Behavior[Command]] {
      override def apply(ctx: JActorContext[Command], command: Command) = f(ctx, command)
    }
  def ps(f: (JActorContext[Command], Signal) ⇒ Unit) =
    new P2[JActorContext[Command], Signal] {
      override def apply(ctx: JActorContext[Command], sig: Signal) = f(ctx, sig)
    }
  def pc(f: (JActorContext[Command], Command) ⇒ Unit) =
    new P2[JActorContext[Command], Command] {
      override def apply(ctx: JActorContext[Command], command: Command) = f(ctx, command)
    }
  def pf(f: PFBuilder[Command, Command] ⇒ PFBuilder[Command, Command]) =
    new F1[PFBuilder[Command, Command], PFBuilder[Command, Command]] {
      override def apply(in: PFBuilder[Command, Command]) = f(in)
    }
  def fi(f: Command ⇒ Command) =
    new FI.Apply[Command, Command] {
      override def apply(in: Command) = f(in)
    }
  def df(f: JActorContext[Command] ⇒ Behavior[Command]) =
    new F1e[JActorContext[Command], Behavior[Command]] {
      override def apply(in: JActorContext[Command]) = f(in)
    }

  trait Lifecycle extends Common {
    "Lifecycle" must {
      "must react to PreStart" in {
        mkCtx(requirePreStart = true)
      }

      "must react to PostStop" in {
        mkCtx().check(PostStop)
      }

      "must react to PostStop after a message" in {
        mkCtx().check(GetSelf).check(PostStop)
      }

      "must react to PreRestart" in {
        mkCtx().check(PreRestart)
      }

      "must react to PreRestart after a message" in {
        mkCtx().check(GetSelf).check(PreRestart)
      }

      "must react to Terminated" in {
        mkCtx().check(Terminated(Inbox("x").ref)(null))
      }

      "must react to Terminated after a message" in {
        mkCtx().check(GetSelf).check(Terminated(Inbox("x").ref)(null))
      }

      "must react to a message after Terminated" in {
        mkCtx().check(Terminated(Inbox("x").ref)(null)).check(GetSelf)
      }
    }
  }

  trait Messages extends Common {
    "Messages" must {
      "react to two messages" in {
        mkCtx().check(Ping).check(Ping)
      }

      "react to a message after missing one" in {
        mkCtx().check(Miss).check(Ping)
      }

      "must react to a message after ignoring one" in {
        mkCtx().check(Ignore).check(Ping)
      }
    }
  }

  trait Unhandled extends Common {
    "Unahndled" must {
      "must return Unhandled" in {
        val Setup(ctx, inbox, aux) = mkCtx()
        Behavior.interpretMessage(ctx.currentBehavior, ctx, Miss) should be(Behavior.UnhandledBehavior)
        inbox.receiveAll() should ===(Missed :: Nil)
        checkAux(Miss, aux)
      }
    }
  }

  trait Stoppable extends Common {
    "Stopping" must {
      "must stop" in {
        val Setup(ctx, inbox, aux) = mkCtx()
        ctx.run(Stop)
        ctx.currentBehavior should be(Behavior.StoppedBehavior)
        checkAux(Stop, aux)
      }
    }
  }

  trait Become extends Common with Unhandled {
    private implicit val inbox = Inbox[State]("state")

    "Becoming" must {
      "must be in state A" in {
        mkCtx().check(GetState()(StateA))
      }

      "must switch to state B" in {
        mkCtx().check(Swap).check(GetState()(StateB))
      }

      "must switch back to state A" in {
        mkCtx().check(Swap).check(Swap).check(GetState()(StateA))
      }
    }
  }

  trait BecomeWithLifecycle extends Become with Lifecycle {
    "Become with lifecycle" must {
      "react to PostStop after swap" in {
        mkCtx().check(Swap).check(PostStop)
      }

      "react to PostStop after a message after swap" in {
        mkCtx().check(Swap).check(GetSelf).check(PostStop)
      }

      "react to PreRestart after swap" in {
        mkCtx().check(Swap).check(PreRestart)
      }

      "react to PreRestart after a message after swap" in {
        mkCtx().check(Swap).check(GetSelf).check(PreRestart)
      }

      "react to Terminated after swap" in {
        mkCtx().check(Swap).check(Terminated(Inbox("x").ref)(null))
      }

      "react to Terminated after a message after swap" in {
        mkCtx().check(Swap).check(GetSelf).check(Terminated(Inbox("x").ref)(null))
      }

      "react to a message after Terminated after swap" in {
        mkCtx().check(Swap).check(Terminated(Inbox("x").ref)(null)).check(GetSelf)
      }
    }
  }

  /**
   * This targets behavior wrappers to ensure that the wrapper does not
   * hold on to the changed behavior. Wrappers must be immutable.
   */
  trait Reuse extends Common {
    "Reuse" must {
      "must be reusable" in {
        val i = init()
        i.mkCtx().check(GetState()(StateA)).check(Swap).check(GetState()(StateB))
        i.mkCtx().check(GetState()(StateA)).check(Swap).check(GetState()(StateB))
      }
    }
  }

}

import BehaviorSpec._

class FullBehaviorSpec extends TypedSpec with Messages with BecomeWithLifecycle with Stoppable {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = mkFull(monitor) → null
}

class ImmutableBehaviorSpec extends TypedSpec with Messages with BecomeWithLifecycle with Stoppable {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
  private def behv(monitor: ActorRef[Event], state: State): Behavior[Command] = {
    SActor.immutable[Command] {
      case (ctx, GetSelf) ⇒
        monitor ! Self(ctx.self)
        SActor.same
      case (_, Miss) ⇒
        monitor ! Missed
        SActor.unhandled
      case (_, Ignore) ⇒
        monitor ! Ignored
        SActor.same
      case (_, Ping) ⇒
        monitor ! Pong
        behv(monitor, state)
      case (_, Swap) ⇒
        monitor ! Swapped
        behv(monitor, state.next)
      case (_, GetState()) ⇒
        monitor ! state
        SActor.same
      case (_, Stop)       ⇒ SActor.stopped
      case (_, _: AuxPing) ⇒ SActor.unhandled
    } onSignal {
      case (ctx, signal) ⇒
        monitor ! GotSignal(signal)
        SActor.same
    }
  }
}

class ImmutableWithSignalScalaBehaviorSpec extends TypedSpec with Messages with BecomeWithLifecycle with Stoppable {

  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor) → null

  def behv(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] =
    SActor.immutable[Command] {
      (ctx, msg) ⇒
        msg match {
          case GetSelf ⇒
            monitor ! Self(ctx.self)
            SActor.same
          case Miss ⇒
            monitor ! Missed
            SActor.unhandled
          case Ignore ⇒
            monitor ! Ignored
            SActor.same
          case Ping ⇒
            monitor ! Pong
            behv(monitor, state)
          case Swap ⇒
            monitor ! Swapped
            behv(monitor, state.next)
          case GetState() ⇒
            monitor ! state
            SActor.same
          case Stop       ⇒ SActor.stopped
          case _: AuxPing ⇒ SActor.unhandled
        }
    } onSignal {
      case (ctx, sig) ⇒
        monitor ! GotSignal(sig)
        SActor.same
    }
}

class ImmutableScalaBehaviorSpec extends TypedSpec with Messages with Become with Stoppable {

  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null

  def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
    SActor.immutable[Command] { (ctx, msg) ⇒
      msg match {
        case GetSelf ⇒
          monitor ! Self(ctx.self)
          SActor.same
        case Miss ⇒
          monitor ! Missed
          SActor.unhandled
        case Ignore ⇒
          monitor ! Ignored
          SActor.same
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState() ⇒
          monitor ! state
          SActor.same
        case Stop       ⇒ SActor.stopped
        case _: AuxPing ⇒ SActor.unhandled
      }
    }
}

class MutableScalaBehaviorSpec extends TypedSpec with Messages with Become with Stoppable {

  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor) → null

  def behv(monitor: ActorRef[Event]): Behavior[Command] =
    SActor.mutable[Command] { ctx ⇒
      new SActor.MutableBehavior[Command] {
        private var state: State = StateA

        override def onMessage(msg: Command): Behavior[Command] = {
          msg match {
            case GetSelf ⇒
              monitor ! Self(ctx.self)
              this
            case Miss ⇒
              monitor ! Missed
              SActor.unhandled
            case Ignore ⇒
              monitor ! Ignored
              SActor.same // this or same works the same way
            case Ping ⇒
              monitor ! Pong
              this
            case Swap ⇒
              monitor ! Swapped
              state = state.next
              this
            case GetState() ⇒
              monitor ! state
              this
            case Stop       ⇒ SActor.stopped
            case _: AuxPing ⇒ SActor.unhandled
          }
        }
      }
    }
}

class WidenedScalaBehaviorSpec extends ImmutableWithSignalScalaBehaviorSpec with Reuse with Siphon {

  import SActor.BehaviorDecorators

  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    val inbox = Inbox[Command]("widenedListener")
    super.behavior(monitor)._1.widen[Command] { case c ⇒ inbox.ref ! c; c } → inbox
  }
}

class DeferredScalaBehaviorSpec extends ImmutableWithSignalScalaBehaviorSpec {
  override type Aux = Inbox[Done]

  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    val inbox = Inbox[Done]("deferredListener")
    (SActor.deferred(ctx ⇒ {
      inbox.ref ! Done
      super.behavior(monitor)._1
    }), inbox)
  }

  override def checkAux(signal: Signal, aux: Aux): Unit =
    aux.receiveAll() should ===(Done :: Nil)
}

class TapScalaBehaviorSpec extends ImmutableWithSignalScalaBehaviorSpec with Reuse with SignalSiphon {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    val inbox = Inbox[Either[Signal, Command]]("tapListener")
    (SActor.tap((_, msg) ⇒ inbox.ref ! Right(msg), (_, sig) ⇒ inbox.ref ! Left(sig), super.behavior(monitor)._1), inbox)
  }
}

class RestarterScalaBehaviorSpec extends ImmutableWithSignalScalaBehaviorSpec with Reuse {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    SActor.supervise(super.behavior(monitor)._1).onFailure(SupervisorStrategy.restart) → null
  }
}

class ImmutableWithSignalJavaBehaviorSpec extends TypedSpec with Messages with BecomeWithLifecycle with Stoppable {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor) → null
  def behv(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] =
    JActor.immutable(
      fc((ctx, msg) ⇒ msg match {
        case GetSelf ⇒
          monitor ! Self(ctx.getSelf)
          SActor.same
        case Miss ⇒
          monitor ! Missed
          SActor.unhandled
        case Ignore ⇒
          monitor ! Ignored
          SActor.same
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState() ⇒
          monitor ! state
          SActor.same
        case Stop       ⇒ SActor.stopped
        case _: AuxPing ⇒ SActor.unhandled
      }),
      fs((ctx, sig) ⇒ {
        monitor ! GotSignal(sig)
        SActor.same
      }))
}

class ImmutableJavaBehaviorSpec extends TypedSpec with Messages with Become with Stoppable {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
  def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
    JActor.immutable {
      fc((ctx, msg) ⇒
        msg match {
          case GetSelf ⇒
            monitor ! Self(ctx.getSelf)
            SActor.same
          case Miss ⇒
            monitor ! Missed
            SActor.unhandled
          case Ignore ⇒
            monitor ! Ignored
            SActor.same
          case Ping ⇒
            monitor ! Pong
            behv(monitor, state)
          case Swap ⇒
            monitor ! Swapped
            behv(monitor, state.next)
          case GetState() ⇒
            monitor ! state
            SActor.same
          case Stop       ⇒ SActor.stopped
          case _: AuxPing ⇒ SActor.unhandled
        })
    }
}

class WidenedJavaBehaviorSpec extends ImmutableWithSignalJavaBehaviorSpec with Reuse with Siphon {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    val inbox = Inbox[Command]("widenedListener")
    JActor.widened(super.behavior(monitor)._1, pf(_.`match`(classOf[Command], fi(x ⇒ {
      inbox.ref ! x
      x
    })))) → inbox
  }
}

class DeferredJavaBehaviorSpec extends ImmutableWithSignalJavaBehaviorSpec {
  override type Aux = Inbox[Done]

  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    val inbox = Inbox[Done]("deferredListener")
    (JActor.deferred(df(ctx ⇒ {
      inbox.ref ! Done
      super.behavior(monitor)._1
    })), inbox)
  }

  override def checkAux(signal: Signal, aux: Aux): Unit =
    aux.receiveAll() should ===(Done :: Nil)
}

class TapJavaBehaviorSpec extends ImmutableWithSignalJavaBehaviorSpec with Reuse with SignalSiphon {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    val inbox = Inbox[Either[Signal, Command]]("tapListener")
    (JActor.tap(
      pc((_, msg) ⇒ inbox.ref ! Right(msg)),
      ps((_, sig) ⇒ inbox.ref ! Left(sig)),
      super.behavior(monitor)._1), inbox)
  }
}

class RestarterJavaBehaviorSpec extends ImmutableWithSignalJavaBehaviorSpec with Reuse {
  override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
    JActor.supervise(super.behavior(monitor)._1)
      .onFailure(classOf[Exception], SupervisorStrategy.restart) → null
  }
}
