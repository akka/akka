/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.InvalidMessageException

import scala.annotation.tailrec
import akka.util.LineNumbers
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.actor.typed.scaladsl.{ ActorContext ⇒ SAC }
import akka.util.OptionVal

/**
 * The behavior of an actor defines how it reacts to the messages that it
 * receives. The message may either be of the type that the Actor declares
 * and which is part of the [[ActorRef]] signature, or it may be a system
 * [[Signal]] that expresses a lifecycle event of either this actor or one of
 * its child actors.
 *
 * Behaviors can be formulated in a number of different ways, either by
 * using the DSLs in [[akka.actor.typed.scaladsl.Behaviors]] and [[akka.actor.typed.javadsl.Behaviors]]
 * or extending the abstract [[ExtensibleBehavior]] class.
 *
 * Closing over ActorContext makes a Behavior immobile: it cannot be moved to
 * another context and executed there, and therefore it cannot be replicated or
 * forked either.
 *
 * This base class is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 *
 * Not for user extension.
 */
@InternalApi
@DoNotInherit
sealed abstract class Behavior[T] {
  /**
   * Narrow the type of this Behavior, which is always a safe operation. This
   * method is necessary to implement the contravariant nature of Behavior
   * (which cannot be expressed directly due to type inference problems).
   */
  final def narrow[U <: T]: Behavior[U] = this.asInstanceOf[Behavior[U]]
}

/**
 * Extension point for implementing custom behaviors in addition to the existing
 * set of behaviors available through the DSLs in [[akka.actor.typed.scaladsl.Behaviors]] and [[akka.actor.typed.javadsl.Behaviors]]
 */
abstract class ExtensibleBehavior[T] extends Behavior[T] {
  /**
   * Process an incoming [[Signal]] and return the next behavior. This means
   * that all lifecycle hooks, ReceiveTimeout, Terminated and Failed messages
   * can initiate a behavior change.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `stopped` will terminate this Behavior
   *  * returning `same` designates to reuse the current Behavior
   *  * returning `unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def receiveSignal(ctx: ActorContext[T], msg: Signal): Behavior[T]

  /**
   * Process an incoming message and return the next behavior.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `stopped` will terminate this Behavior
   *  * returning `same` designates to reuse the current Behavior
   *  * returning `unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def receiveMessage(ctx: ActorContext[T], msg: T): Behavior[T]

}

object Behavior {

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def same[T]: Behavior[T] = SameBehavior.asInstanceOf[Behavior[T]]
  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def unhandled[T]: Behavior[T] = UnhandledBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * The PostStop signal that results from stopping this actor will be passed to the
   * current behavior. All other messages and signals will effectively be
   * ignored.
   */
  def stopped[T]: Behavior[T] = StoppedBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * The PostStop signal that results from stopping this actor will be passed to the
   * given `postStop` behavior. All other messages and signals will effectively be
   * ignored.
   */
  def stopped[T](postStop: Behavior[T]): Behavior[T] =
    new StoppedBehavior(OptionVal.Some(postStop))

  /**
   * A behavior that treats every incoming message as unhandled.
   */
  def empty[T]: Behavior[T] = EmptyBehavior.asInstanceOf[Behavior[T]]

  /**
   * A behavior that ignores every incoming message and returns “same”.
   */
  def ignore[T]: Behavior[T] = IgnoreBehavior.asInstanceOf[Behavior[T]]

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] object EmptyBehavior extends Behavior[Any] {
    override def toString = "Empty"
  }

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] object IgnoreBehavior extends Behavior[Any] {
    override def toString = "Ignore"
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] object UnhandledBehavior extends Behavior[Nothing] {
    override def toString = "Unhandled"
  }

  /**
   * INTERNAL API
   * Used to create untyped props from behaviours, or directly returning an untyped props that implements this behavior.
   */
  @InternalApi
  private[akka] abstract class UntypedPropsBehavior[T] extends Behavior[T] {
    /** INTERNAL API */
    @InternalApi private[akka] def untypedProps(props: Props): akka.actor.Props
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val unhandledSignal: PartialFunction[(ActorContext[Nothing], Signal), Behavior[Nothing]] = {
    case (_, _) ⇒ UnhandledBehavior
  }

  /**
   * INTERNAL API.
   * Not placed in internal.BehaviorImpl because Behavior is sealed.
   */
  @InternalApi
  @DoNotInherit
  private[akka] class DeferredBehavior[T](val factory: SAC[T] ⇒ Behavior[T]) extends Behavior[T] {

    /** start the deferred behavior */
    @throws(classOf[Exception])
    def apply(ctx: ActorContext[T]): Behavior[T] = factory(ctx.asScala)

    override def toString: String = s"Deferred(${LineNumbers(factory)})"
  }
  object DeferredBehavior {
    def apply[T](factory: SAC[T] ⇒ Behavior[T]) =
      new DeferredBehavior[T](factory)
  }

  /**
   * INTERNAL API.
   */
  private[akka] object SameBehavior extends Behavior[Nothing] {
    override def toString = "Same"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object StoppedBehavior extends StoppedBehavior[Nothing](OptionVal.None)

  /**
   * INTERNAL API: When the cell is stopping this behavior is used, so
   * that PostStop can be sent to previous behavior from `finishTerminate`.
   */
  private[akka] class StoppedBehavior[T](val postStop: OptionVal[Behavior[T]]) extends Behavior[T] {
    override def toString = "Stopped"
  }

  /**
   * Given a possibly special behavior (same or unhandled) and a
   * “current” behavior (which defines the meaning of encountering a `same`
   * behavior) this method computes the next behavior, suitable for passing a
   * message or signal.
   */
  @tailrec
  def canonicalize[T](behavior: Behavior[T], current: Behavior[T], ctx: ActorContext[T]): Behavior[T] =
    behavior match {
      case SameBehavior                  ⇒ current
      case UnhandledBehavior             ⇒ current
      case deferred: DeferredBehavior[T] ⇒ canonicalize(deferred(ctx), deferred, ctx)
      case other                         ⇒ other
    }

  /**
   * INTERNAL API
   *
   * Return special behaviors as is, start deferred, if behavior is "non-special" apply the wrap function `f` to get
   * and return the result from that. Useful for cases where a [[Behavior]] implementation that is decorating another
   * behavior has processed a message and needs to re-wrap the resulting behavior with itself.
   */
  @InternalApi
  @tailrec
  private[akka] def wrap[T, U](currentBehavior: Behavior[_], nextBehavior: Behavior[T], ctx: ActorContext[T])(f: Behavior[T] ⇒ Behavior[U]): Behavior[U] =
    nextBehavior match {
      case SameBehavior | `currentBehavior` ⇒ same
      case UnhandledBehavior                ⇒ unhandled
      case StoppedBehavior                  ⇒ stopped
      case deferred: DeferredBehavior[T]    ⇒ wrap(currentBehavior, start(deferred, ctx), ctx)(f)
      case other                            ⇒ f(other)
    }

  /**
   * Starts deferred behavior and nested deferred behaviors until a non deferred behavior is reached
   * and that is then returned.
   */
  @tailrec
  def start[T](behavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
    behavior match {
      case innerDeferred: DeferredBehavior[T] ⇒ start(innerDeferred(ctx), ctx)
      case _                                  ⇒ behavior
    }
  }

  /**
   * Validate the given behavior as a suitable initial actor behavior; most
   * notably the behavior can neither be `same` nor `unhandled`. Starting
   * out with a `Stopped` behavior is allowed, though.
   */
  def validateAsInitial[T](behavior: Behavior[T]): Behavior[T] =
    behavior match {
      case SameBehavior | UnhandledBehavior ⇒
        throw new IllegalArgumentException(s"cannot use $behavior as initial behavior")
      case x ⇒ x
    }

  /**
   * Returns true if the given behavior is not stopped.
   */
  def isAlive[T](behavior: Behavior[T]): Boolean = behavior match {
    case _: StoppedBehavior[_] ⇒ false
    case _                     ⇒ true
  }

  /**
   * Returns true if the given behavior is the special `unhandled` marker.
   */
  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq UnhandledBehavior

  /**
   * Returns true if the given behavior is the special `Unhandled` marker.
   */
  def isDeferred[T](behavior: Behavior[T]): Boolean = behavior match {
    case _: DeferredBehavior[T] ⇒ true
    case _                      ⇒ false
  }

  /**
   * Execute the behavior with the given message
   */
  def interpretMessage[T](behavior: Behavior[T], ctx: ActorContext[T], msg: T): Behavior[T] =
    interpret(behavior, ctx, msg)

  /**
   * Execute the behavior with the given signal
   */
  def interpretSignal[T](behavior: Behavior[T], ctx: ActorContext[T], signal: Signal): Behavior[T] =
    interpret(behavior, ctx, signal)

  private def interpret[T](behavior: Behavior[T], ctx: ActorContext[T], msg: Any): Behavior[T] = {
    behavior match {
      case null ⇒ throw new InvalidMessageException("[null] is not an allowed message")
      case SameBehavior | UnhandledBehavior ⇒
        throw new IllegalArgumentException(s"cannot execute with [$behavior] as behavior")
      case _: UntypedPropsBehavior[_] ⇒
        throw new IllegalArgumentException(s"cannot wrap behavior [$behavior] in " +
          "Behaviors.setup, Behaviors.supervise or similar")
      case d: DeferredBehavior[_] ⇒ throw new IllegalArgumentException(s"deferred [$d] should not be passed to interpreter")
      case IgnoreBehavior         ⇒ SameBehavior.asInstanceOf[Behavior[T]]
      case s: StoppedBehavior[T]  ⇒ s
      case EmptyBehavior          ⇒ UnhandledBehavior.asInstanceOf[Behavior[T]]
      case ext: ExtensibleBehavior[T] ⇒
        val possiblyDeferredResult = msg match {
          case signal: Signal ⇒ ext.receiveSignal(ctx, signal)
          case m              ⇒ ext.receiveMessage(ctx, m.asInstanceOf[T])
        }
        start(possiblyDeferredResult, ctx)
    }
  }

  /**
   * INTERNAL API
   *
   * Execute the behavior with the given messages (or signals).
   * The returned [[Behavior]] from each processed message is used for the next message.
   */
  @InternalApi private[akka] def interpretMessages[T](behavior: Behavior[T], ctx: ActorContext[T], messages: Iterator[T]): Behavior[T] = {
    @tailrec def interpretOne(b: Behavior[T]): Behavior[T] = {
      val b2 = Behavior.start(b, ctx)
      if (!Behavior.isAlive(b2) || !messages.hasNext) b2
      else {
        val nextB = messages.next() match {
          case sig: Signal ⇒ Behavior.interpretSignal(b2, ctx, sig)
          case msg         ⇒ Behavior.interpretMessage(b2, ctx, msg)
        }
        interpretOne(Behavior.canonicalize(nextB, b, ctx)) // recursive
      }
    }

    interpretOne(Behavior.start(behavior, ctx))
  }

}
