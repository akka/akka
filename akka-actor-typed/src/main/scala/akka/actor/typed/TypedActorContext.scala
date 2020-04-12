/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.annotation.{ DoNotInherit, InternalApi }

import scala.annotation.unchecked.uncheckedVariance

/**
 * This trait is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 *
 * Not for user extension.
 */
@DoNotInherit
trait TypedActorContext[T] {
  // this should be a pure interface, i.e. only abstract methods

  /**
   * Get the `javadsl` of this `ActorContext`.
   */
  def asJava: javadsl.ActorContext[T]

  /**
   * Get the `scaladsl` of this `ActorContext`.
   */
  def asScala: scaladsl.ActorContext[T]

  /**
   * Narrow the type of this `TypedActorContext`, which is always a safe operation.
   */
  def narrow[U <: T]: TypedActorContext[U]

  /**
   * Unsafe utility method for widening the type accepted by this TypedActorContext.
   * */
  def unsafeUpcast[U >: T @uncheckedVariance]: TypedActorContext[U]

  /**
   * Unsafe utility method for changing the type accepted by this TypedActorContext.
   * */
  @InternalApi private[akka] def unsafeCast[U]: TypedActorContext[U]
}
