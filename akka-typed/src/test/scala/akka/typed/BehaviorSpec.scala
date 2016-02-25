/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import org.scalautils.ConversionCheckedTripleEquals

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
  case class GetState(replyTo: ActorRef[State]) extends Command
  object GetState {
    def apply()(implicit inbox: Inbox.SyncInbox[State]): GetState = GetState(inbox.ref)
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

  trait State { def next: State }
  val StateA: State = new State { override def toString = "StateA"; override def next = StateB }
  val StateB: State = new State { override def toString = "StateB"; override def next = StateA }

  trait Common {
    def behavior(monitor: ActorRef[Event]): Behavior[Command]

    case class Setup(ctx: EffectfulActorContext[Command], inbox: Inbox.SyncInbox[Event])

    protected def mkCtx(requirePreStart: Boolean = false, factory: (ActorRef[Event]) ⇒ Behavior[Command] = behavior) = {
      val inbox = Inbox.sync[Event]("evt")
      val ctx = new EffectfulActorContext("ctx", Props(factory(inbox.ref)), system)
      val msgs = inbox.receiveAll()
      if (requirePreStart)
        msgs should ===(GotSignal(PreStart) :: Nil)
      Setup(ctx, inbox)
    }

    protected implicit class Check(val setup: Setup) {
      def check(signal: Signal): Setup = {
        setup.ctx.signal(signal)
        setup.inbox.receiveAll() should ===(GotSignal(signal) :: Nil)
        setup
      }
      def check(command: Command): Setup = {
        setup.ctx.run(command)
        setup.inbox.receiveAll() should ===(command.expectedResponse(setup.ctx))
        setup
      }
      def check[T](command: Command, aux: T*)(implicit inbox: Inbox.SyncInbox[T]): Setup = {
        setup.ctx.run(command)
        setup.inbox.receiveAll() should ===(command.expectedResponse(setup.ctx))
        inbox.receiveAll() should ===(aux)
        setup
      }
      def check2(command: Command): Setup = {
        setup.ctx.run(command)
        val expected = command.expectedResponse(setup.ctx)
        setup.inbox.receiveAll() should ===(expected ++ expected)
        setup
      }
      def check2[T](command: Command, aux: T*)(implicit inbox: Inbox.SyncInbox[T]): Setup = {
        setup.ctx.run(command)
        val expected = command.expectedResponse(setup.ctx)
        setup.inbox.receiveAll() should ===(expected ++ expected)
        inbox.receiveAll() should ===(aux ++ aux)
        setup
      }
    }

    protected val ex = new Exception("mine!")
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
      mkCtx().check(PreRestart(ex))
    }

    def `must react to PreRestart after a message`(): Unit = {
      mkCtx().check(GetSelf).check(PreRestart(ex))
    }

    def `must react to PostRestart`(): Unit = {
      mkCtx().check(PostRestart(ex))
    }

    def `must react to a message after PostRestart`(): Unit = {
      mkCtx().check(PostRestart(ex)).check(GetSelf)
    }

    def `must react to Failed`(): Unit = {
      val setup @ Setup(ctx, inbox) = mkCtx()
      val f = Failed(ex, inbox.ref)
      setup.check(f)
      f.getDecision should ===(Failed.Restart)
    }

    def `must react to Failed after a message`(): Unit = {
      val setup @ Setup(ctx, inbox) = mkCtx().check(GetSelf)
      val f = Failed(ex, inbox.ref)
      setup.check(f)
      f.getDecision should ===(Failed.Restart)
    }

    def `must react to a message after Failed`(): Unit = {
      val setup @ Setup(ctx, inbox) = mkCtx()
      val f = Failed(ex, inbox.ref)
      setup.check(f)
      f.getDecision should ===(Failed.Restart)
      setup.check(GetSelf)
    }

    def `must react to ReceiveTimeout`(): Unit = {
      mkCtx().check(ReceiveTimeout)
    }

    def `must react to ReceiveTimeout after a message`(): Unit = {
      mkCtx().check(GetSelf).check(ReceiveTimeout)
    }

    def `must react to a message after ReceiveTimeout`(): Unit = {
      mkCtx().check(ReceiveTimeout).check(GetSelf)
    }

    def `must react to Terminated`(): Unit = {
      mkCtx().check(Terminated(Inbox.sync("x").ref))
    }

    def `must react to Terminated after a message`(): Unit = {
      mkCtx().check(GetSelf).check(Terminated(Inbox.sync("x").ref))
    }

    def `must react to a message after Terminated`(): Unit = {
      mkCtx().check(Terminated(Inbox.sync("x").ref)).check(GetSelf)
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
      val Setup(ctx, inbox) = mkCtx()
      ctx.currentBehavior.message(ctx, Miss) should ===(ScalaDSL.Unhandled[Command])
      inbox.receiveAll() should ===(Missed :: Nil)
    }
  }

  trait Stoppable extends Common {
    def `must stop`(): Unit = {
      val Setup(ctx, inbox) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should ===(ScalaDSL.Stopped[Command])
    }
  }

  trait Become extends Common with Unhandled {
    private implicit val inbox = Inbox.sync[State]("state")

    def `must be in state A`(): Unit = {
      mkCtx().check(GetState(), StateA)
    }

    def `must switch to state B`(): Unit = {
      mkCtx().check(Swap).check(GetState(), StateB)
    }

    def `must switch back to state A`(): Unit = {
      mkCtx().check(Swap).check(Swap).check(GetState(), StateA)
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
      mkCtx().check(Swap).check(PreRestart(ex))
    }

    def `must react to PreRestart after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(PreRestart(ex))
    }

    def `must react to a message after PostRestart after swap`(): Unit = {
      mkCtx().check(PostRestart(ex)).check(Swap).check(GetSelf)
    }

    def `must react to Failed after swap`(): Unit = {
      val setup @ Setup(ctx, inbox) = mkCtx().check(Swap)
      val f = Failed(ex, inbox.ref)
      setup.check(f)
      f.getDecision should ===(Failed.Restart)
    }

    def `must react to Failed after a message after swap`(): Unit = {
      val setup @ Setup(ctx, inbox) = mkCtx().check(Swap).check(GetSelf)
      val f = Failed(ex, inbox.ref)
      setup.check(f)
      f.getDecision should ===(Failed.Restart)
    }

    def `must react to a message after Failed after swap`(): Unit = {
      val setup @ Setup(ctx, inbox) = mkCtx().check(Swap)
      val f = Failed(ex, inbox.ref)
      setup.check(f)
      f.getDecision should ===(Failed.Restart)
      setup.check(GetSelf)
    }

    def `must react to ReceiveTimeout after swap`(): Unit = {
      mkCtx().check(Swap).check(ReceiveTimeout)
    }

    def `must react to ReceiveTimeout after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(ReceiveTimeout)
    }

    def `must react to a message after ReceiveTimeout after swap`(): Unit = {
      mkCtx().check(Swap).check(ReceiveTimeout).check(GetSelf)
    }

    def `must react to Terminated after swap`(): Unit = {
      mkCtx().check(Swap).check(Terminated(Inbox.sync("x").ref))
    }

    def `must react to Terminated after a message after swap`(): Unit = {
      mkCtx().check(Swap).check(GetSelf).check(Terminated(Inbox.sync("x").ref))
    }

    def `must react to a message after Terminated after swap`(): Unit = {
      mkCtx().check(Swap).check(Terminated(Inbox.sync("x").ref)).check(GetSelf)
    }
  }

  private def mkFull(monitor: ActorRef[Event], state: State = StateA): Behavior[Command] = {
    import ScalaDSL.{ Full, Msg, Sig, Same, Unhandled, Stopped }
    Full {
      case Sig(ctx, signal) ⇒
        monitor ! GotSignal(signal)
        signal match {
          case f: Failed ⇒ f.decide(Failed.Restart)
          case _         ⇒
        }
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
      case Msg(ctx, GetState(replyTo)) ⇒
        replyTo ! state
        Same
      case Msg(ctx, Stop) ⇒ Stopped
    }
  }

  object `A Full Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = mkFull(monitor)
  }

  object `A FullTotal Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = behv(monitor, StateA)
    private def behv(monitor: ActorRef[Event], state: State): Behavior[Command] = {
      import ScalaDSL.{ FullTotal, Msg, Sig, Same, Unhandled, Stopped }
      FullTotal {
        case Sig(ctx, signal) ⇒
          monitor ! GotSignal(signal)
          signal match {
            case f: Failed ⇒ f.decide(Failed.Restart)
            case _         ⇒
          }
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
        case Msg(_, GetState(replyTo)) ⇒
          replyTo ! state
          Same
        case Msg(_, Stop)       ⇒ Stopped
        case Msg(_, _: AuxPing) ⇒ Unhandled
      }
    }
  }

  object `A Widened Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Widened(mkFull(monitor), { case x ⇒ x })
  }

  object `A ContextAware Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.ContextAware(ctx ⇒ mkFull(monitor))
  }

  object `A SelfAware Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.SelfAware(self ⇒ mkFull(monitor))
  }

  object `A non-matching Tap Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Tap({ case null ⇒ }, mkFull(monitor))
  }

  object `A matching Tap Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Tap({ case _ ⇒ }, mkFull(monitor))
  }

  object `A SynchronousSelf Behavior` extends Messages with BecomeWithLifecycle with Stoppable {
    import ScalaDSL._

    implicit private val inbox = Inbox.sync[Command]("syncself")

    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      SynchronousSelf(self ⇒ mkFull(monitor))

    private def behavior2(monitor: ActorRef[Event]): Behavior[Command] = {
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
      SynchronousSelf(self ⇒ Or(mkFull(monitor), first(self)))
    }

    def `must send messages to itself and stop correctly`(): Unit = {
      val Setup(ctx, _) = mkCtx(factory = behavior2).check[Command](AuxPing(42), Seq(42, 0, 1, 2, 3) map AuxPing: _*)
      ctx.run(AuxPing(4))
      inbox.receiveAll() should ===(AuxPing(4) :: Nil)
      ctx.currentBehavior should ===(Stopped[Command])
    }
  }

  trait And extends Common {
    private implicit val inbox = Inbox.sync[State]("and")

    private def behavior2(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.And(mkFull(monitor), mkFull(monitor))

    def `must pass message to both parts`(): Unit = {
      mkCtx(factory = behavior2).check2(Swap).check2[State](GetState(), StateB)
    }

    def `must half-terminate`(): Unit = {
      val Setup(ctx, inbox) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should ===(ScalaDSL.Empty[Command])
    }
  }

  object `A Behavior combined with And (left)` extends Messages with BecomeWithLifecycle with And {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.And(mkFull(monitor), ScalaDSL.Empty)
  }

  object `A Behavior combined with And (right)` extends Messages with BecomeWithLifecycle with And {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.And(ScalaDSL.Empty, mkFull(monitor))
  }

  trait Or extends Common {
    private def strange(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Full {
        case ScalaDSL.Msg(_, Ping | AuxPing(_)) ⇒
          monitor ! Pong
          ScalaDSL.Unhandled
      }

    private def behavior2(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Or(mkFull(monitor), strange(monitor))

    private def behavior3(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Or(strange(monitor), mkFull(monitor))

    def `must pass message only to first interested party`(): Unit = {
      mkCtx(factory = behavior2).check(Ping).check(AuxPing(0))
    }

    def `must pass message through both if first is uninterested`(): Unit = {
      mkCtx(factory = behavior3).check2(Ping).check(AuxPing(0))
    }

    def `must half-terminate`(): Unit = {
      val Setup(ctx, inbox) = mkCtx()
      ctx.run(Stop)
      ctx.currentBehavior should ===(ScalaDSL.Empty[Command])
    }
  }

  object `A Behavior combined with Or (left)` extends Messages with BecomeWithLifecycle with Or {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Or(mkFull(monitor), ScalaDSL.Empty)
  }

  object `A Behavior combined with Or (right)` extends Messages with BecomeWithLifecycle with Or {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Or(ScalaDSL.Empty, mkFull(monitor))
  }

  object `A Partial Behavior` extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = behv(monitor, StateA)
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
        case GetState(replyTo) ⇒
          replyTo ! state
          ScalaDSL.Same
        case Stop ⇒ ScalaDSL.Stopped
      }
  }

  object `A Total Behavior` extends Messages with Become with Stoppable {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] = behv(monitor, StateA)
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
        case GetState(replyTo) ⇒
          replyTo ! state
          ScalaDSL.Same
        case Stop       ⇒ ScalaDSL.Stopped
        case _: AuxPing ⇒ ScalaDSL.Unhandled
      }
  }

  object `A Static Behavior` extends Messages {
    override def behavior(monitor: ActorRef[Event]): Behavior[Command] =
      ScalaDSL.Static {
        case Ping        ⇒ monitor ! Pong
        case Miss        ⇒ monitor ! Missed
        case Ignore      ⇒ monitor ! Ignored
        case GetSelf     ⇒
        case Swap        ⇒
        case GetState(_) ⇒
        case Stop        ⇒
        case _: AuxPing  ⇒
      }
  }
}
