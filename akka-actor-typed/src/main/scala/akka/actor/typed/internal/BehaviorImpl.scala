/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import akka.util.LineNumbers
import akka.annotation.InternalApi
import akka.actor.typed.{ TypedActorContext => AC }
import akka.actor.typed.scaladsl.{ ActorContext => SAC }
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object BehaviorTags {

  // optimization - by keeping an identifier for each concrete subtype of behavior
  // without gaps we can do table switches instead of instance of checks when interpreting
  // note that these must be compile time constants for it to work
  final val ExtensibleBehavior = 1
  final val EmptyBehavior = 2
  final val IgnoreBehavior = 3
  final val UnhandledBehavior = 4
  final val DeferredBehavior = 5
  final val SameBehavior = 6
  final val FailedBehavior = 7
  final val StoppedBehavior = 8

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BehaviorImpl {

  implicit class ContextAs[T](val ctx: AC[T]) extends AnyVal {
    def as[U]: AC[U] = ctx.asInstanceOf[AC[U]]
  }

  def widened[O, I](behavior: Behavior[I], matcher: PartialFunction[O, I]): Behavior[O] =
    intercept(() => WidenedInterceptor(matcher))(behavior)

  def same[T]: Behavior[T] = SameBehavior.unsafeCast[T]

  def unhandled[T]: Behavior[T] = UnhandledBehavior.unsafeCast[T]

  def stopped[T]: Behavior[T] = StoppedBehavior.unsafeCast[T]

  def stopped[T](postStop: () => Unit): Behavior[T] =
    new StoppedBehavior[T](OptionVal.Some((_: TypedActorContext[T]) => postStop()))

  def empty[T]: Behavior[T] = EmptyBehavior.unsafeCast[T]

  def ignore[T]: Behavior[T] = IgnoreBehavior.unsafeCast[T]

  def failed[T](cause: Throwable): Behavior[T] = new FailedBehavior(cause).asInstanceOf[Behavior[T]]

  val unhandledSignal: PartialFunction[(TypedActorContext[Nothing], Signal), Behavior[Nothing]] = {
    case (_, _) => UnhandledBehavior
  }

  private object EmptyBehavior extends Behavior[Any](BehaviorTags.EmptyBehavior) {
    override def toString = "Empty"
  }

  private object IgnoreBehavior extends Behavior[Any](BehaviorTags.IgnoreBehavior) {
    override def toString = "Ignore"
  }

  object UnhandledBehavior extends Behavior[Nothing](BehaviorTags.UnhandledBehavior) {
    override def toString = "Unhandled"
  }

  object SameBehavior extends Behavior[Nothing](BehaviorTags.SameBehavior) {
    override def toString = "Same"
  }

  class FailedBehavior(val cause: Throwable) extends Behavior[Nothing](BehaviorTags.FailedBehavior) {
    override def toString: String = s"Failed($cause)"
  }

  object StoppedBehavior extends StoppedBehavior[Nothing](OptionVal.None)

  /**
   * When the cell is stopping this behavior is used, so
   * that PostStop can be sent to previous behavior from `finishTerminate`.
   */
  private[akka] sealed class StoppedBehavior[T](val postStop: OptionVal[TypedActorContext[T] => Unit])
      extends Behavior[T](BehaviorTags.StoppedBehavior) {

    def onPostStop(ctx: TypedActorContext[T]): Unit = {
      postStop match {
        case OptionVal.Some(callback) => callback(ctx)
        case OptionVal.None           =>
      }
    }

    override def toString = "Stopped" + {
      postStop match {
        case OptionVal.Some(callback) => s"(${LineNumbers(callback)})"
        case _                        => "()"
      }
    }
  }

  abstract class DeferredBehavior[T] extends Behavior[T](BehaviorTags.DeferredBehavior) {
    def apply(ctx: TypedActorContext[T]): Behavior[T]
  }

  object DeferredBehavior {
    def apply[T](factory: SAC[T] => Behavior[T]): Behavior[T] =
      new DeferredBehavior[T] {
        def apply(ctx: TypedActorContext[T]): Behavior[T] = factory(ctx.asScala)
        override def toString: String = s"Deferred(${LineNumbers(factory)})"
      }
  }

  class ReceiveBehavior[T](
      val onMessage: (SAC[T], T) => Behavior[T],
      onSignal: PartialFunction[(SAC[T], Signal), Behavior[T]] =
        BehaviorImpl.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])
      extends ExtensibleBehavior[T] {

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] =
      onSignal.applyOrElse(
        (ctx.asScala, msg),
        BehaviorImpl.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])

    override def receive(ctx: AC[T], msg: T) = onMessage(ctx.asScala, msg)

    override def toString = s"Receive(${LineNumbers(onMessage)})"
  }

  /**
   * Similar to [[ReceiveBehavior]] however `onMessage` does not accept context.
   * We implement it separately in order to be able to avoid wrapping each function in
   * another function which drops the context parameter.
   */
  class ReceiveMessageBehavior[T](
      val onMessage: T => Behavior[T],
      onSignal: PartialFunction[(SAC[T], Signal), Behavior[T]] =
        BehaviorImpl.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])
      extends ExtensibleBehavior[T] {

    override def receive(ctx: AC[T], msg: T) = onMessage(msg)

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] =
      onSignal.applyOrElse(
        (ctx.asScala, msg),
        BehaviorImpl.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])

    override def toString = s"ReceiveMessage(${LineNumbers(onMessage)})"
  }

  /**
   * Intercept messages and signals for a `behavior` by first passing them to a [[akka.actor.typed.BehaviorInterceptor]]
   *
   * When a behavior returns a new behavior as a result of processing a signal or message and that behavior already contains
   * the same interceptor (defined by the `isSame` method on the `BehaviorInterceptor`) only the innermost interceptor
   * is kept. This is to protect against stack overflow when recursively defining behaviors.
   */
  def intercept[O, I](interceptor: () => BehaviorInterceptor[O, I])(behavior: Behavior[I]): Behavior[O] =
    InterceptorImpl(interceptor, behavior)

  class OrElseBehavior[T](first: Behavior[T], second: Behavior[T]) extends ExtensibleBehavior[T] {

    override def receive(ctx: AC[T], msg: T): Behavior[T] = {
      Behavior.interpretMessage(first, ctx, msg) match {
        case _: UnhandledBehavior.type => Behavior.interpretMessage(second, ctx, msg)
        case handled                   => handled
      }
    }

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] = {
      val result: Behavior[T] = try {
        Behavior.interpretSignal(first, ctx, msg)
      } catch {
        case _: DeathPactException =>
          // since we don't know what kind of concrete Behavior `first` is, if it is intercepted etc.
          // the only way we can fallback to second behavior if Terminated wasn't handled is to
          // catch the DeathPact here and pretend like it was just `unhandled`
          BehaviorImpl.unhandled
      }

      result match {
        case _: UnhandledBehavior.type => Behavior.interpretSignal(second, ctx, msg)
        case handled                   => handled
      }
    }
  }

}
