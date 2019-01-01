/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.typed
import akka.actor.typed.Behavior.{ SameBehavior, UnhandledBehavior }
import akka.actor.typed.internal.TimerSchedulerImpl.TimerMsg
import akka.actor.typed.{ TypedActorContext, ActorRef, Behavior, BehaviorInterceptor, ExtensibleBehavior, PreRestart, Signal }
import akka.annotation.InternalApi
import akka.util.LineNumbers

/**
 * Provides the impl of any behavior that could nest another behavior
 *
 * INTERNAL API
 */
@InternalApi
private[akka] object InterceptorImpl {

  def apply[O, I](interceptor: BehaviorInterceptor[O, I], nestedBehavior: Behavior[I]): Behavior[O] = {
    Behavior.DeferredBehavior[O] { ctx ⇒
      val interceptorBehavior = new InterceptorImpl[O, I](interceptor, nestedBehavior)
      interceptorBehavior.preStart(ctx)
    }
  }
}

/**
 * Provides the impl of any behavior that could nest another behavior
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final class InterceptorImpl[O, I](val interceptor: BehaviorInterceptor[O, I], val nestedBehavior: Behavior[I])
  extends ExtensibleBehavior[O] with WrappingBehavior[O, I] {

  import BehaviorInterceptor._

  private val preStartTarget: PreStartTarget[I] = new PreStartTarget[I] {
    override def start(ctx: TypedActorContext[_]): Behavior[I] = {
      Behavior.start[I](nestedBehavior, ctx.asInstanceOf[TypedActorContext[I]])
    }
  }

  private val receiveTarget: ReceiveTarget[I] = new ReceiveTarget[I] {
    override def apply(ctx: TypedActorContext[_], msg: I): Behavior[I] =
      Behavior.interpretMessage(nestedBehavior, ctx.asInstanceOf[TypedActorContext[I]], msg)

    override def signalRestart(ctx: TypedActorContext[_]): Unit =
      Behavior.interpretSignal(nestedBehavior, ctx.asInstanceOf[TypedActorContext[I]], PreRestart)
  }

  private val signalTarget = new SignalTarget[I] {
    override def apply(ctx: TypedActorContext[_], signal: Signal): Behavior[I] =
      Behavior.interpretSignal(nestedBehavior, ctx.asInstanceOf[TypedActorContext[I]], signal)
  }

  // invoked pre-start to start/de-duplicate the initial behavior stack
  def preStart(ctx: typed.TypedActorContext[O]): Behavior[O] = {
    val started = interceptor.aroundStart(ctx, preStartTarget)
    deduplicate(started, ctx)
  }

  override def replaceNested(newNested: Behavior[I]): Behavior[O] =
    new InterceptorImpl(interceptor, newNested)

  override def receive(ctx: typed.TypedActorContext[O], msg: O): Behavior[O] = {
    val interceptedResult = interceptor.aroundReceive(ctx, msg, receiveTarget)
    deduplicate(interceptedResult, ctx)
  }

  override def receiveSignal(ctx: typed.TypedActorContext[O], signal: Signal): Behavior[O] = {
    val interceptedResult = interceptor.aroundSignal(ctx, signal, signalTarget)
    deduplicate(interceptedResult, ctx)
  }

  private def deduplicate(interceptedResult: Behavior[I], ctx: TypedActorContext[O]): Behavior[O] = {
    val started = Behavior.start(interceptedResult, ctx.asInstanceOf[TypedActorContext[I]])
    if (started == UnhandledBehavior || started == SameBehavior || !Behavior.isAlive(started)) {
      started.unsafeCast[O]
    } else {
      // returned behavior could be nested in setups, so we need to start before we deduplicate
      val duplicateInterceptExists = Behavior.existsInStack(started) {
        case i: InterceptorImpl[O, I] if interceptor.isSame(i.interceptor.asInstanceOf[BehaviorInterceptor[Any, Any]]) ⇒ true
        case _ ⇒ false
      }

      if (duplicateInterceptExists) started.unsafeCast[O]
      else new InterceptorImpl[O, I](interceptor, started)
    }
  }

  override def toString(): String = s"Interceptor($interceptor, $nestedBehavior)"
}

/**
 * Fire off any incoming message to another actor before receiving it ourselves.
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final case class MonitorInterceptor[T](actorRef: ActorRef[T]) extends BehaviorInterceptor[T, T] {
  import BehaviorInterceptor._

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    actorRef ! msg
    target(ctx, msg)
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    target(ctx, signal)
  }

  // only once to the same actor in the same behavior stack
  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    case MonitorInterceptor(`actorRef`) ⇒ true
    case _                              ⇒ false
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object WidenedInterceptor {

  private final val _any2null = (_: Any) ⇒ null
  private final def any2null[T] = _any2null.asInstanceOf[Any ⇒ T]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final case class WidenedInterceptor[O, I](matcher: PartialFunction[O, I]) extends BehaviorInterceptor[O, I] {
  import WidenedInterceptor._
  import BehaviorInterceptor._

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    // If they use the same pf instance we can allow it, to have one way to workaround defining
    // "recursive" narrowed behaviors.
    case WidenedInterceptor(`matcher`) ⇒ true
    case WidenedInterceptor(otherMatcher) ⇒
      // there is no safe way to allow this
      throw new IllegalStateException("Widen can only be used one time in the same behavior stack. " +
        s"One defined in ${LineNumbers(matcher)}, and another in ${LineNumbers(otherMatcher)}")
    case _ ⇒ false
  }

  def aroundReceive(ctx: TypedActorContext[O], msg: O, target: ReceiveTarget[I]): Behavior[I] = {
    // widen would wrap the TimerMessage, which would be wrong, see issue #25318
    msg match {
      case t: TimerMsg ⇒ throw new IllegalArgumentException(
        s"Timers and widen can't be used together, [${t.key}]. See issue #25318")
      case _ ⇒ ()
    }

    matcher.applyOrElse(msg, any2null) match {
      case null        ⇒ Behavior.unhandled
      case transformed ⇒ target(ctx, transformed)
    }
  }

  def aroundSignal(ctx: TypedActorContext[O], signal: Signal, target: SignalTarget[I]): Behavior[I] =
    target(ctx, signal)

  override def toString: String = s"Widen(${LineNumbers(matcher)})"
}
