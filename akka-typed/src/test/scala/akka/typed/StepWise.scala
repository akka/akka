/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.Deadline

/**
 * This object contains tools for building step-wise behaviors for formulating
 * a linearly progressing protocol.
 *
 * Example:
 * {{{
 * import scala.concurrent.duration._
 *
 * StepWise[Command] { (ctx, startWith) =>
 *   startWith {
 *     val child = ctx.spawn(...)
 *     child ! msg
 *     child
 *   }.expectMessage(100.millis) { (reply, child) =>
 *     target ! GotReply(reply)
 *   }
 * }
 * }}}
 *
 * State can be passed from one step to the next by returning it as is
 * demonstrated with the `child` ActorRef in the example.
 *
 * This way of writing Actors can be very useful when writing Actor-based
 * test procedures for actor systems, hence also the possibility to expect
 * failures (see [[StepWise.Steps#expectFailure]]).
 */
@deprecated("to be replaced by process DSL", "2.4-M2")
object StepWise {
  import ScalaDSL._

  sealed trait AST
  private final case class Thunk(f: () ⇒ Any) extends AST
  private final case class ThunkV(f: Any ⇒ Any) extends AST
  private final case class Message(timeout: FiniteDuration, f: (Any, Any) ⇒ Any, trace: Trace) extends AST
  private final case class MultiMessage(timeout: FiniteDuration, count: Int, f: (Seq[Any], Any) ⇒ Any, trace: Trace) extends AST
  private final case class Termination(timeout: FiniteDuration, f: (Terminated, Any) ⇒ Any, trace: Trace) extends AST
  private final case class Multi(timeout: FiniteDuration, count: Int, f: (Seq[Either[Signal, Any]], Any) ⇒ Any, trace: Trace) extends AST

  private case object ReceiveTimeout

  private sealed trait Trace {
    def getStackTrace: Array[StackTraceElement]
    protected def getFrames: Array[StackTraceElement] =
      Thread.currentThread.getStackTrace.dropWhile { elem ⇒
        val name = elem.getClassName
        name.startsWith("java.lang.Thread") || name.startsWith("akka.typed.StepWise")
      }
  }
  private class WithTrace extends Trace {
    private val trace = getFrames
    def getStackTrace = trace
  }
  private object WithoutTrace extends Trace {
    def getStackTrace = getFrames
  }

  final case class Steps[T, U](ops: List[AST], keepTraces: Boolean) {
    private def getTrace(): Trace =
      if (keepTraces) new WithTrace
      else WithoutTrace

    def apply[V](thunk: U ⇒ V): Steps[T, V] =
      copy(ops = ThunkV(thunk.asInstanceOf[Any ⇒ Any]) :: ops)

    def keep(thunk: U ⇒ Unit): Steps[T, U] =
      copy(ops = ThunkV(value ⇒ { thunk.asInstanceOf[Any ⇒ Any](value); value }) :: ops)

    def expectMessage[V](timeout: FiniteDuration)(f: (T, U) ⇒ V): Steps[T, V] =
      copy(ops = Message(timeout, f.asInstanceOf[(Any, Any) ⇒ Any], getTrace()) :: ops)

    def expectMultipleMessages[V](timeout: FiniteDuration, count: Int)(f: (Seq[T], U) ⇒ V): Steps[T, V] =
      copy(ops = MultiMessage(timeout, count, f.asInstanceOf[(Seq[Any], Any) ⇒ Any], getTrace()) :: ops)

    def expectTermination[V](timeout: FiniteDuration)(f: (Terminated, U) ⇒ V): Steps[T, V] =
      copy(ops = Termination(timeout, f.asInstanceOf[(Terminated, Any) ⇒ Any], getTrace()) :: ops)

    def expectMulti[V](timeout: FiniteDuration, count: Int)(f: (Seq[Either[Signal, T]], U) ⇒ V): Steps[T, V] =
      copy(ops = Multi(timeout, count, f.asInstanceOf[(Seq[Either[Signal, Any]], Any) ⇒ Any], getTrace()) :: ops)

    def expectMessageKeep(timeout: FiniteDuration)(f: (T, U) ⇒ Unit): Steps[T, U] =
      copy(ops = Message(timeout, (msg, value) ⇒ { f.asInstanceOf[(Any, Any) ⇒ Any](msg, value); value }, getTrace()) :: ops)

    def expectMultipleMessagesKeep(timeout: FiniteDuration, count: Int)(f: (Seq[T], U) ⇒ Unit): Steps[T, U] =
      copy(ops = MultiMessage(timeout, count, (msgs, value) ⇒ { f.asInstanceOf[(Seq[Any], Any) ⇒ Any](msgs, value); value }, getTrace()) :: ops)

    def expectTerminationKeep(timeout: FiniteDuration)(f: (Terminated, U) ⇒ Unit): Steps[T, U] =
      copy(ops = Termination(timeout, (t, value) ⇒ { f.asInstanceOf[(Terminated, Any) ⇒ Any](t, value); value }, getTrace()) :: ops)

    def expectMultiKeep(timeout: FiniteDuration, count: Int)(f: (Seq[Either[Signal, T]], U) ⇒ Unit): Steps[T, U] =
      copy(ops = Multi(timeout, count, (msgs, value) ⇒ { f.asInstanceOf[(Seq[Either[Signal, Any]], Any) ⇒ Any](msgs, value); value }, getTrace()) :: ops)

    def withKeepTraces(b: Boolean): Steps[T, U] = copy(keepTraces = b)
  }

  class StartWith[T](keepTraces: Boolean) {
    def apply[U](thunk: ⇒ U): Steps[T, U] = Steps(Thunk(() ⇒ thunk) :: Nil, keepTraces)
    def withKeepTraces(b: Boolean): StartWith[T] = new StartWith(b)
  }

  def apply[T](f: (ActorContext[T], StartWith[T]) ⇒ Steps[T, _]): Behavior[T] =
    Full[Any] {
      case Sig(ctx, PreStart) ⇒ run(ctx, f(ctx.asInstanceOf[ActorContext[T]], new StartWith(keepTraces = false)).ops.reverse, ())
    }.narrow

  private def throwTimeout(trace: Trace, message: String): Nothing =
    throw new TimeoutException(message) {
      override def fillInStackTrace(): Throwable = {
        setStackTrace(trace.getStackTrace)
        this
      }
    }

  private def throwIllegalState(trace: Trace, message: String): Nothing =
    throw new IllegalStateException(message) {
      override def fillInStackTrace(): Throwable = {
        setStackTrace(trace.getStackTrace)
        this
      }
    }

  private def run[T](ctx: ActorContext[Any], ops: List[AST], value: Any): Behavior[Any] =
    ops match {
      case Thunk(f) :: tail  ⇒ run(ctx, tail, f())
      case ThunkV(f) :: tail ⇒ run(ctx, tail, f(value))
      case Message(t, f, trace) :: tail ⇒
        ctx.setReceiveTimeout(t, ReceiveTimeout)
        Full {
          case Msg(_, ReceiveTimeout) ⇒ throwTimeout(trace, s"timeout of $t expired while waiting for a message")
          case Msg(_, msg) ⇒
            ctx.cancelReceiveTimeout()
            run(ctx, tail, f(msg, value))
          case Sig(_, other) ⇒ throwIllegalState(trace, s"unexpected $other while waiting for a message")
        }
      case MultiMessage(t, c, f, trace) :: tail ⇒
        val deadline = Deadline.now + t
        def behavior(count: Int, acc: List[Any]): Behavior[Any] = {
          ctx.setReceiveTimeout(deadline.timeLeft, ReceiveTimeout)
          Full {
            case Msg(_, ReceiveTimeout) ⇒
              throwTimeout(trace, s"timeout of $t expired while waiting for $c messages (got only $count)")
            case Msg(_, msg) ⇒
              val nextCount = count + 1
              if (nextCount == c) {
                ctx.cancelReceiveTimeout()
                run(ctx, tail, f((msg :: acc).reverse, value))
              } else behavior(nextCount, msg :: acc)
            case Sig(_, other) ⇒ throwIllegalState(trace, s"unexpected $other while waiting for $c messages (got $count valid ones)")
          }
        }
        behavior(0, Nil)
      case Multi(t, c, f, trace) :: tail ⇒
        val deadline = Deadline.now + t
        def behavior(count: Int, acc: List[Either[Signal, Any]]): Behavior[Any] = {
          ctx.setReceiveTimeout(deadline.timeLeft, ReceiveTimeout)
          Full {
            case Msg(_, ReceiveTimeout) ⇒
              throwTimeout(trace, s"timeout of $t expired while waiting for $c messages (got only $count)")
            case Msg(_, msg) ⇒
              val nextCount = count + 1
              if (nextCount == c) {
                ctx.cancelReceiveTimeout()
                run(ctx, tail, f((Right(msg) :: acc).reverse, value))
              } else behavior(nextCount, Right(msg) :: acc)
            case Sig(_, other) ⇒
              val nextCount = count + 1
              if (nextCount == c) {
                ctx.cancelReceiveTimeout()
                run(ctx, tail, f((Left(other) :: acc).reverse, value))
              } else behavior(nextCount, Left(other) :: acc)
          }
        }
        behavior(0, Nil)
      case Termination(t, f, trace) :: tail ⇒
        ctx.setReceiveTimeout(t, ReceiveTimeout)
        Full {
          case Msg(_, ReceiveTimeout) ⇒ throwTimeout(trace, s"timeout of $t expired while waiting for termination")
          case Sig(_, t: Terminated) ⇒
            ctx.cancelReceiveTimeout()
            run(ctx, tail, f(t, value))
          case other ⇒ throwIllegalState(trace, s"unexpected $other while waiting for termination")
        }
      case Nil ⇒ Stopped
    }
}

abstract class StepWise
