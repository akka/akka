/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.annotation.InternalApi

/**
 * The behavior of an actor defines how it reacts to the messages that it
 * receives. The message may either be of the type that the Actor declares
 * and which is part of the [[ActorRef]] signature, or it may be a system
 * [[Signal]] that expresses a lifecycle event of either this actor or one of
 * its child actors.
 *
 * Behaviors can be formulated in a number of different ways, either by
 * creating a derived class or by employing factory methods like the ones
 * in the [[ScalaDSL$]] object.
 *
 * Closing over ActorContext makes a Behavior immobile: it cannot be moved to
 * another context and executed there, and therefore it cannot be replicated or
 * forked either.
 */
abstract class Behavior[T] {
  /**
   * Process an incoming [[Signal]] and return the next behavior. This means
   * that all lifecycle hooks, ReceiveTimeout, Terminated and Failed messages
   * can initiate a behavior change.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `Stopped` will terminate this Behavior
   *  * returning `Same` designates to reuse the current Behavior
   *  * returning `Unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def management(ctx: ActorContext[T], msg: Signal): Behavior[T]

  /**
   * Process an incoming message and return the next behavior.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `Stopped` will terminate this Behavior
   *  * returning `Same` designates to reuse the current Behavior
   *  * returning `Unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def message(ctx: ActorContext[T], msg: T): Behavior[T]

  /**
   * Narrow the type of this Behavior, which is always a safe operation. This
   * method is necessary to implement the contravariant nature of Behavior
   * (which cannot be expressed directly due to type inference problems).
   */
  def narrow[U <: T]: Behavior[U] = this.asInstanceOf[Behavior[U]]
}

object Behavior {

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object emptyBehavior extends Behavior[Any] {
    override def management(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = ScalaDSL.Unhandled
    override def message(ctx: ActorContext[Any], msg: Any): Behavior[Any] = ScalaDSL.Unhandled
    override def toString = "Empty"
  }

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object ignoreBehavior extends Behavior[Any] {
    override def management(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = ScalaDSL.Same
    override def message(ctx: ActorContext[Any], msg: Any): Behavior[Any] = ScalaDSL.Same
    override def toString = "Ignore"
  }

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object unhandledBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Unhandled"
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val unhandledSignal: (ActorContext[Nothing], Signal) ⇒ Behavior[Nothing] =
    (_, _) ⇒ unhandledBehavior

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object sameBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Same"
  }

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object stoppedBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = {
      assert(
        msg == PostStop || msg.isInstanceOf[Terminated],
        s"stoppedBehavior received $msg (only PostStop or Terminated expected)")
      this
    }
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Stopped"
  }

  /**
   * Given a possibly special behavior (same or unhandled) and a
   * “current” behavior (which defines the meaning of encountering a `Same`
   * behavior) this method computes the next behavior, suitable for passing a
   * message or signal.
   */
  def canonicalize[T](behavior: Behavior[T], current: Behavior[T]): Behavior[T] =
    behavior match {
      case `sameBehavior`      ⇒ current
      case `unhandledBehavior` ⇒ current
      case other               ⇒ other
    }

  /**
   * Validate the given behavior as a suitable initial actor behavior; most
   * notably the behavior can neither be `Same` nor `Unhandled`. Starting
   * out with a `Stopped` behavior is allowed, though.
   */
  def validateAsInitial[T](behavior: Behavior[T]): Behavior[T] =
    behavior match {
      case `sameBehavior` | `unhandledBehavior` ⇒
        throw new IllegalArgumentException(s"cannot use $behavior as initial behavior")
      case x ⇒ x
    }

  /**
   * Validate the given behavior as initial, pass it a [[PreStart]] message
   * and canonicalize the result.
   */
  def preStart[T](behavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
    val b = validateAsInitial(behavior)
    if (isAlive(b)) canonicalize(b.management(ctx, PreStart), b) else b
  }

  /**
   * Returns true if the given behavior is not stopped.
   */
  def isAlive[T](behavior: Behavior[T]): Boolean = behavior ne stoppedBehavior

  /**
   * Returns true if the given behavior is the special `Unhandled` marker.
   */
  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq unhandledBehavior
}
