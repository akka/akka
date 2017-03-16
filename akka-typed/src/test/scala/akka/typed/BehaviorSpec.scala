/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.typed.scaladsl.{ Actor ⇒ SActor }
import akka.typed.javadsl.{ Actor ⇒ JActor, ActorContext ⇒ JActorContext }
import akka.japi.function.{ Function ⇒ F1e, Function2 ⇒ F2, Procedure2 ⇒ P2 }
import akka.japi.pf.{ FI, PFBuilder }
import java.util.function.{ Function ⇒ F1 }

class BehaviorSpec extends TypedSpec {

  sealed trait Command {
    def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Nil
  }
  case object GetSelf extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Self(ctx.self) :: Nil
  }
  // Behavior under test must return Unhandled
  case object Miss extends Command {
    override def expectedResponse(ctx: ActorContext[Command]): Seq[Event] = Missed :: Nil
  }
  // Behavior under test must return Same
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

  sealed trait State extends Event { def next: State }
  val StateA: State = new State { override def toString = "StateA"; override def next = StateB }
  val StateB: State = new State { override def toString = "StateB"; override def next = StateA }

  trait Common {
    type Aux >: Null <: AnyRef
    def system: ActorSystem[TypedSpec.Command]
    def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux)
    def checkAux(signal: Signal, aux: Aux): Unit = ()
    def checkAux(command: Command, aux: Aux): Unit = ()

    case class Init(behv: Behavior[Command], inbox: Inbox[Event], aux: Aux) {
      def mkCtx(requirePreStart: Boolean = false): Setup = {
        val ctx = new EffectfulActorContext("ctx", behv, 1000, system)
        val msgs = inbox.receiveAll()
        if (requirePreStart) msgs should ===(GotSignal(PreStart) :: Nil)
        checkAux(PreStart, aux)
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
      init().mkCtx(requirePreStart)

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

  trait Lifecycle extends Common {
    def `must react to PreStart`(): Unit = {
      mkCtx(requirePreStart = true)
    }

    def `must react to PostStop`(): Unit = {
      mkCtx().check(PostStop)
    }

    def `must react to PostStop after a message`(): Unit = {
      mkCtx().check(GetSelf).check(PostStop)
    }

    def `must react to PreRestart`(): Unit = {
      mkCtx().check(PreRestart)
    }

    def `must react to PreRestart after a message`(): Unit = {
      mkCtx().check(GetSelf).check(PreRestart)
    }

    def `must react to Terminated`(): Unit = {
      mkCtx().check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to Terminated after a message`(): Unit = {
      mkCtx().check(GetSelf).check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to a message after Terminated`(): Unit = {
      mkCtx().check(Terminated(Inbox("x").ref)(null)).check(GetSelf)
    }
  }

  trait Messages extends Common {
    def `must react to two messages`(): Unit = {
      mkCtx().check(Ping).check(Ping)
    }

    def `must react to a message after missing one`(): Unit = {
      mkCtx().check(Miss).check(Ping)
    }

    def `must react to a message after ignoring one`(): Unit = {
      mkCtx().check(Ignore).check(Ping)
    }
  }

  trait Unhandled extends Common {
    def `must return Unhandled`(): Unit = {
      val Setup(ctx, inbox, aux) = mkCtx()
      ctx.currentBehavior.message(ctx, Miss) should be(Behavior.unhandledBehavior)
      inbox.receiveAll() should ===(Missed :: Nil)
      checkAux(Miss, aux)
    }
  }

  trait Stoppable extends Common {
    def `must stop`(): Unit = {
      val Setup(ctx, inbox, aux) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should be(Behavior.stoppedBehavior)
      checkAux(Stop, aux)
    }
  }

  trait Become extends Common with Unhandled {
    private implicit val inbox = Inbox[State]("state")

    def `must be in state A`(): Unit = {
      mkCtx().check(GetState()(StateA))
    }

    def `must switch to state B`(): Unit = {
      mkCtx().check(Swap).check(GetState()(StateB))
    }

    def `must switch back to state A`(): Unit = {
      mkCtx().check(Swap).check(Swap).check(GetState()(StateA))
    }
  }

  trait BecomeWithLifecycle extends Become with Lifecycle {
    def `must react to PostStop after swap`(): Unit = {
      mkCtx().check(Swap).check(PostStop)
    }

    def `must react to PostStop after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(PostStop)
    }

    def `must react to PreRestart after swap`(): Unit = {
      mkCtx().check(Swap).check(PreRestart)
    }

    def `must react to PreRestart after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(PreRestart)
    }

    def `must react to Terminated after swap`(): Unit = {
      mkCtx().check(Swap).check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to Terminated after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(Terminated(Inbox("x").ref)(null))
    }

    def `must react to a message after Terminated after swap`(): Unit = {
      mkCtx().check(Swap).check(Terminated(Inbox("x").ref)(null)).check(GetSelf)
    }
  }

  /**
   * This targets behavior wrappers to ensure that the wrapper does not
   * hold on to the changed behavior. Wrappers must be immutable.
   */
  trait Reuse extends Common {
    def `must be reusable`(): Unit = {
      val i = init()
      i.mkCtx().check(GetState()(StateA)).check(Swap).check(GetState()(StateB))
      i.mkCtx().check(GetState()(StateA)).check(Swap).check(GetState()(StateB))
    }
  }

  private def mkFull(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] = {
    import ScalaDSL.{ Full, Msg, Sig, Same, Unhandled, Stopped }
    Full {
      case Sig(ctx, signal) ⇒
        monitor ! GotSignal(signal)
        Same
      case Msg(ctx, GetSelf) ⇒
        monitor ! Self(ctx.self)
        Same
      case Msg(ctx, Miss) ⇒
        monitor ! Missed
        Unhandled
      case Msg(ctx, Ignore) ⇒
        monitor ! Ignored
        Same
      case Msg(ctx, Ping) ⇒
        monitor ! Pong
        mkFull(monitor, state)
      case Msg(ctx, Swap) ⇒
        monitor ! Swapped
        mkFull(monitor, state.next)
      case Msg(ctx, GetState()) ⇒
        monitor ! state
        Same
      case Msg(ctx, Stop) ⇒ Stopped
    }
  }

  trait FullBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = mkFull(monitor) → null
  }
  object `A Full Behavior (native)` extends FullBehavior with NativeSystem
  object `A Full Behavior (adapted)` extends FullBehavior with AdaptedSystem

  trait FullTotalBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
    private def behv(monitor: ActorRef[Event], state: State): Behavior[Command] = {
      import ScalaDSL.{ FullTotal, Msg, Sig, Same, Unhandled, Stopped }
      FullTotal {
        case Sig(ctx, signal) ⇒
          monitor ! GotSignal(signal)
          Same
        case Msg(ctx, GetSelf) ⇒
          monitor ! Self(ctx.self)
          Same
        case Msg(_, Miss) ⇒
          monitor ! Missed
          Unhandled
        case Msg(_, Ignore) ⇒
          monitor ! Ignored
          Same
        case Msg(_, Ping) ⇒
          monitor ! Pong
          behv(monitor, state)
        case Msg(_, Swap) ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case Msg(_, GetState()) ⇒
          monitor ! state
          Same
        case Msg(_, Stop)       ⇒ Stopped
        case Msg(_, _: AuxPing) ⇒ Unhandled
      }
    }
  }
  object `A FullTotal Behavior (native)` extends FullTotalBehavior with NativeSystem
  object `A FullTotal Behavior (adapted)` extends FullTotalBehavior with AdaptedSystem

  trait WidenedBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (ScalaDSL.Widened(mkFull(monitor), { case x ⇒ x }), null)
  }
  object `A Widened Behavior (native)` extends WidenedBehavior with NativeSystem
  object `A Widened Behavior (adapted)` extends WidenedBehavior with AdaptedSystem

  trait ContextAwareBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (ScalaDSL.ContextAware(ctx ⇒ mkFull(monitor)), null)
  }
  object `A ContextAware Behavior (native)` extends ContextAwareBehavior with NativeSystem
  object `A ContextAware Behavior (adapted)` extends ContextAwareBehavior with AdaptedSystem

  trait SelfAwareBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (ScalaDSL.SelfAware(self ⇒ mkFull(monitor)), null)
  }
  object `A SelfAware Behavior (native)` extends SelfAwareBehavior with NativeSystem
  object `A SelfAware Behavior (adapted)` extends SelfAwareBehavior with AdaptedSystem

  trait NonMatchingTapBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (ScalaDSL.Tap({ case null ⇒ }, mkFull(monitor)), null)
  }
  object `A non-matching Tap Behavior (native)` extends NonMatchingTapBehavior with NativeSystem
  object `A non-matching Tap Behavior (adapted)` extends NonMatchingTapBehavior with AdaptedSystem

  trait MatchingTapBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (ScalaDSL.Tap({ case _ ⇒ }, mkFull(monitor)), null)
  }
  object `A matching Tap Behavior (native)` extends MatchingTapBehavior with NativeSystem
  object `A matching Tap Behavior (adapted)` extends MatchingTapBehavior with AdaptedSystem

  trait SynchronousSelfBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    import ScalaDSL._

    type Aux = Inbox[Command]

    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (SynchronousSelf(self ⇒ mkFull(monitor)), null)

    private def behavior2(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[Command]("syncself")
      def first(self: ActorRef[Command]) = Tap.monitor(inbox.ref, Partial[Command] {
        case AuxPing(id) ⇒ { self ! AuxPing(0); second(self) }
      })
      def second(self: ActorRef[Command]) = Partial[Command] {
        case AuxPing(0) ⇒ { self ! AuxPing(1); Same }
        case AuxPing(1) ⇒ { self ! AuxPing(2); third(self) }
      }
      def third(self: ActorRef[Command]) = Partial[Command] {
        case AuxPing(2) ⇒ { self ! AuxPing(3); Unhandled }
        case AuxPing(3) ⇒ { self ! Ping; Same }
        case AuxPing(4) ⇒ { self ! Stop; Stopped }
      }
      (SynchronousSelf(self ⇒ Or(mkFull(monitor), first(self))), inbox)
    }

    override def checkAux(cmd: Command, aux: Aux) =
      (cmd, aux) match {
        case (AuxPing(42), i: Inbox[_]) ⇒ i.receiveAll() should ===(Seq(42, 0, 1, 2, 3) map AuxPing: Seq[Command])
        case (AuxPing(4), i: Inbox[_])  ⇒ i.receiveAll() should ===(AuxPing(4) :: Nil)
        case _                          ⇒ // ignore
      }

    def `must send messages to itself and stop correctly`(): Unit = {
      val Setup(ctx, _, _) = init(behavior2).mkCtx().check(AuxPing(42))
      ctx.run(AuxPing(4))
      ctx.currentBehavior should ===(Stopped[Command])
    }
  }
  object `A SynchronousSelf Behavior (native)` extends SynchronousSelfBehavior with NativeSystem
  object `A SynchronousSelf Behavior (adapted)` extends SynchronousSelfBehavior with AdaptedSystem

  trait And extends Common {

    private def behavior2(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.And(mkFull(monitor), mkFull(monitor)) → null

    def `must pass message to both parts`(): Unit = {
      init(behavior2).mkCtx().check2(Swap).check2(GetState()(StateB))
    }

    def `must half-terminate`(): Unit = {
      val Setup(ctx, inbox, _) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should ===(ScalaDSL.Empty[Command])
    }
  }

  trait BehaviorAndLeft extends Messages with BecomeWithLifecycle with And {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.And(mkFull(monitor), ScalaDSL.Empty) → null
  }
  object `A Behavior combined with And (left, native)` extends BehaviorAndLeft with NativeSystem
  object `A Behavior combined with And (left, adapted)` extends BehaviorAndLeft with NativeSystem

  trait BehaviorAndRight extends Messages with BecomeWithLifecycle with And {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.And(ScalaDSL.Empty, mkFull(monitor)) → null
  }
  object `A Behavior combined with And (right, native)` extends BehaviorAndRight with NativeSystem
  object `A Behavior combined with And (right, adapted)` extends BehaviorAndRight with NativeSystem

  trait Or extends Common {
    private def strange(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Full {
        case ScalaDSL.Msg(_, Ping | AuxPing(_)) ⇒
          monitor ! Pong
          ScalaDSL.Unhandled
      }

    private def behavior2(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.Or(mkFull(monitor), strange(monitor)) → null

    private def behavior3(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.Or(strange(monitor), mkFull(monitor)) → null

    def `must pass message only to first interested party`(): Unit = {
      init(behavior2).mkCtx().check(Ping).check(AuxPing(0))
    }

    def `must pass message through both if first is uninterested`(): Unit = {
      init(behavior3).mkCtx().check2(Ping).check(AuxPing(0))
    }

    def `must half-terminate`(): Unit = {
      val Setup(ctx, inbox, _) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should ===(ScalaDSL.Empty[Command])
    }
  }

  trait BehaviorOrLeft extends Messages with BecomeWithLifecycle with Or {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.Or(mkFull(monitor), ScalaDSL.Empty) → null
  }
  object `A Behavior combined with Or (left, native)` extends BehaviorOrLeft with NativeSystem
  object `A Behavior combined with Or (left, adapted)` extends BehaviorOrLeft with NativeSystem

  trait BehaviorOrRight extends Messages with BecomeWithLifecycle with Or {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      ScalaDSL.Or(ScalaDSL.Empty, mkFull(monitor)) → null
  }
  object `A Behavior combined with Or (right, native)` extends BehaviorOrRight with NativeSystem
  object `A Behavior combined with Or (right, adapted)` extends BehaviorOrRight with NativeSystem

  trait PartialBehavior extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
    def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
      ScalaDSL.Partial {
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Miss ⇒
          monitor ! Missed
          ScalaDSL.Unhandled
        case Ignore ⇒
          monitor ! Ignored
          ScalaDSL.Same
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState() ⇒
          monitor ! state
          ScalaDSL.Same
        case Stop ⇒ ScalaDSL.Stopped
      }
  }
  object `A Partial Behavior (native)` extends PartialBehavior with NativeSystem
  object `A Partial Behavior (adapted)` extends PartialBehavior with AdaptedSystem

  trait TotalBehavior extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
    def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
      ScalaDSL.Total {
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Miss ⇒
          monitor ! Missed
          ScalaDSL.Unhandled
        case Ignore ⇒
          monitor ! Ignored
          ScalaDSL.Same
        case GetSelf ⇒ ScalaDSL.Unhandled
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState() ⇒
          monitor ! state
          ScalaDSL.Same
        case Stop       ⇒ ScalaDSL.Stopped
        case _: AuxPing ⇒ ScalaDSL.Unhandled
      }
  }
  object `A Total Behavior (native)` extends TotalBehavior with NativeSystem
  object `A Total Behavior (adapted)` extends TotalBehavior with AdaptedSystem

  trait StaticBehavior extends Messages {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (ScalaDSL.Static {
        case Ping       ⇒ monitor ! Pong
        case Miss       ⇒ monitor ! Missed
        case Ignore     ⇒ monitor ! Ignored
        case GetSelf    ⇒
        case Swap       ⇒
        case GetState() ⇒
        case Stop       ⇒
        case _: AuxPing ⇒
      }, null)
  }
  object `A Static Behavior (native)` extends StaticBehavior with NativeSystem
  object `A Static Behavior (adapted)` extends StaticBehavior with AdaptedSystem

  trait SignalOrMessageScalaBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor) → null
    def behv(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] =
      SActor.SignalOrMessage((ctx, sig) ⇒ {
        monitor ! GotSignal(sig)
        SActor.Same
      }, (ctx, msg) ⇒ msg match {
        case GetSelf ⇒
          monitor ! Self(ctx.self)
          SActor.Same
        case Miss ⇒
          monitor ! Missed
          SActor.Unhandled
        case Ignore ⇒
          monitor ! Ignored
          SActor.Same
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState() ⇒
          monitor ! state
          SActor.Same
        case Stop       ⇒ SActor.Stopped
        case _: AuxPing ⇒ SActor.Unhandled
      })
  }
  object `A SignalOrMessage Behavior (scala,native)` extends SignalOrMessageScalaBehavior with NativeSystem
  object `A SignalOrMessage Behavior (scala,adapted)` extends SignalOrMessageScalaBehavior with AdaptedSystem

  trait StatefulScalaBehavior extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
    def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
      SActor.Stateful { (ctx, msg) ⇒
        msg match {
          case GetSelf ⇒
            monitor ! Self(ctx.self)
            SActor.Same
          case Miss ⇒
            monitor ! Missed
            SActor.Unhandled
          case Ignore ⇒
            monitor ! Ignored
            SActor.Same
          case Ping ⇒
            monitor ! Pong
            behv(monitor, state)
          case Swap ⇒
            monitor ! Swapped
            behv(monitor, state.next)
          case GetState() ⇒
            monitor ! state
            SActor.Same
          case Stop       ⇒ SActor.Stopped
          case _: AuxPing ⇒ SActor.Unhandled
        }
      }
  }
  object `A Stateful Behavior (scala,native)` extends StatefulScalaBehavior with NativeSystem
  object `A Stateful Behavior (scala,adapted)` extends StatefulScalaBehavior with AdaptedSystem

  trait StatelessScalaBehavior extends Messages {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (SActor.Stateless { (ctx, msg) ⇒
        msg match {
          case GetSelf    ⇒ monitor ! Self(ctx.self)
          case Miss       ⇒ monitor ! Missed
          case Ignore     ⇒ monitor ! Ignored
          case Ping       ⇒ monitor ! Pong
          case Swap       ⇒ monitor ! Swapped
          case GetState() ⇒ monitor ! StateA
          case Stop       ⇒
          case _: AuxPing ⇒
        }
      }, null)
  }
  object `A Stateless Behavior (scala,native)` extends StatelessScalaBehavior with NativeSystem
  object `A Stateless Behavior (scala,adapted)` extends StatelessScalaBehavior with AdaptedSystem

  trait WidenedScalaBehavior extends SignalOrMessageScalaBehavior with Reuse with Siphon {
    import SActor.BehaviorDecorators

    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[Command]("widenedListener")
      super.behavior(monitor)._1.widen[Command] { case c ⇒ inbox.ref ! c; c } → inbox
    }
  }
  object `A widened Behavior (scala,native)` extends WidenedScalaBehavior with NativeSystem
  object `A widened Behavior (scala,adapted)` extends WidenedScalaBehavior with AdaptedSystem

  trait DeferredScalaBehavior extends SignalOrMessageScalaBehavior {
    override type Aux = Inbox[PreStart]

    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[PreStart]("deferredListener")
      (SActor.Deferred(ctx ⇒ {
        inbox.ref ! PreStart
        super.behavior(monitor)._1
      }), inbox)
    }

    override def checkAux(signal: Signal, aux: Aux): Unit =
      signal match {
        case PreStart ⇒ aux.receiveAll() should ===(PreStart :: Nil)
        case _        ⇒ aux.receiveAll() should ===(Nil)
      }
  }
  object `A deferred Behavior (scala,native)` extends DeferredScalaBehavior with NativeSystem
  object `A deferred Behavior (scala,adapted)` extends DeferredScalaBehavior with AdaptedSystem

  trait TapScalaBehavior extends SignalOrMessageScalaBehavior with Reuse with SignalSiphon {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[Either[Signal, Command]]("tapListener")
      (SActor.Tap(
        (_, sig) ⇒ inbox.ref ! Left(sig),
        (_, msg) ⇒ inbox.ref ! Right(msg),
        super.behavior(monitor)._1
      ), inbox)
    }
  }
  object `A tap Behavior (scala,native)` extends TapScalaBehavior with NativeSystem
  object `A tap Behavior (scala,adapted)` extends TapScalaBehavior with AdaptedSystem

  trait RestarterScalaBehavior extends SignalOrMessageScalaBehavior with Reuse {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      SActor.Restarter[Exception]().wrap(super.behavior(monitor)._1) → null
    }
  }
  object `A restarter Behavior (scala,native)` extends RestarterScalaBehavior with NativeSystem
  object `A restarter Behavior (scala,adapted)` extends RestarterScalaBehavior with AdaptedSystem

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

  trait SignalOrMessageJavaBehavior extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor) → null
    def behv(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] =
      JActor.signalOrMessage(fs((ctx, sig) ⇒ {
        monitor ! GotSignal(sig)
        SActor.Same
      }), fc((ctx, msg) ⇒ msg match {
        case GetSelf ⇒
          monitor ! Self(ctx.getSelf)
          SActor.Same
        case Miss ⇒
          monitor ! Missed
          SActor.Unhandled
        case Ignore ⇒
          monitor ! Ignored
          SActor.Same
        case Ping ⇒
          monitor ! Pong
          behv(monitor, state)
        case Swap ⇒
          monitor ! Swapped
          behv(monitor, state.next)
        case GetState() ⇒
          monitor ! state
          SActor.Same
        case Stop       ⇒ SActor.Stopped
        case _: AuxPing ⇒ SActor.Unhandled
      }))
  }
  object `A SignalOrMessage Behavior (java,native)` extends SignalOrMessageJavaBehavior with NativeSystem
  object `A SignalOrMessage Behavior (java,adapted)` extends SignalOrMessageJavaBehavior with AdaptedSystem

  trait StatefulJavaBehavior extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = behv(monitor, StateA) → null
    def behv(monitor: ActorRef[Event], state: State): Behavior[Command] =
      JActor.stateful {
        fc((ctx, msg) ⇒
          msg match {
            case GetSelf ⇒
              monitor ! Self(ctx.getSelf)
              SActor.Same
            case Miss ⇒
              monitor ! Missed
              SActor.Unhandled
            case Ignore ⇒
              monitor ! Ignored
              SActor.Same
            case Ping ⇒
              monitor ! Pong
              behv(monitor, state)
            case Swap ⇒
              monitor ! Swapped
              behv(monitor, state.next)
            case GetState() ⇒
              monitor ! state
              SActor.Same
            case Stop       ⇒ SActor.Stopped
            case _: AuxPing ⇒ SActor.Unhandled
          })
      }
  }
  object `A Stateful Behavior (java,native)` extends StatefulJavaBehavior with NativeSystem
  object `A Stateful Behavior (java,adapted)` extends StatefulJavaBehavior with AdaptedSystem

  trait StatelessJavaBehavior extends Messages {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) =
      (JActor.stateless {
        pc((ctx, msg) ⇒
          msg match {
            case GetSelf    ⇒ monitor ! Self(ctx.getSelf)
            case Miss       ⇒ monitor ! Missed
            case Ignore     ⇒ monitor ! Ignored
            case Ping       ⇒ monitor ! Pong
            case Swap       ⇒ monitor ! Swapped
            case GetState() ⇒ monitor ! StateA
            case Stop       ⇒
            case _: AuxPing ⇒
          })
      }, null)
  }
  object `A Stateless Behavior (java,native)` extends StatelessJavaBehavior with NativeSystem
  object `A Stateless Behavior (java,adapted)` extends StatelessJavaBehavior with AdaptedSystem

  trait WidenedJavaBehavior extends SignalOrMessageJavaBehavior with Reuse with Siphon {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[Command]("widenedListener")
      JActor.widened(super.behavior(monitor)._1, pf(_.`match`(classOf[Command], fi(x ⇒ { inbox.ref ! x; x })))) → inbox
    }
  }
  object `A widened Behavior (java,native)` extends WidenedJavaBehavior with NativeSystem
  object `A widened Behavior (java,adapted)` extends WidenedJavaBehavior with AdaptedSystem

  trait DeferredJavaBehavior extends SignalOrMessageJavaBehavior {
    override type Aux = Inbox[PreStart]

    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[PreStart]("deferredListener")
      (JActor.deferred(df(ctx ⇒ {
        inbox.ref ! PreStart
        super.behavior(monitor)._1
      })), inbox)
    }

    override def checkAux(signal: Signal, aux: Aux): Unit =
      signal match {
        case PreStart ⇒ aux.receiveAll() should ===(PreStart :: Nil)
        case _        ⇒ aux.receiveAll() should ===(Nil)
      }
  }
  object `A deferred Behavior (java,native)` extends DeferredJavaBehavior with NativeSystem
  object `A deferred Behavior (java,adapted)` extends DeferredJavaBehavior with AdaptedSystem

  trait TapJavaBehavior extends SignalOrMessageJavaBehavior with Reuse with SignalSiphon {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      val inbox = Inbox[Either[Signal, Command]]("tapListener")
      (JActor.tap(
        ps((_, sig) ⇒ inbox.ref ! Left(sig)),
        pc((_, msg) ⇒ inbox.ref ! Right(msg)),
        super.behavior(monitor)._1
      ), inbox)
    }
  }
  object `A tap Behavior (java,native)` extends TapJavaBehavior with NativeSystem
  object `A tap Behavior (java,adapted)` extends TapJavaBehavior with AdaptedSystem

  trait RestarterJavaBehavior extends SignalOrMessageJavaBehavior with Reuse {
    override def behavior(monitor: ActorRef[Event]): (Behavior[Command], Aux) = {
      JActor.restarter(classOf[Exception], JActor.OnFailure.RESTART, super.behavior(monitor)._1) → null
    }
  }
  object `A restarter Behavior (java,native)` extends RestarterJavaBehavior with NativeSystem
  object `A restarter Behavior (java,adapted)` extends RestarterJavaBehavior with AdaptedSystem

}
