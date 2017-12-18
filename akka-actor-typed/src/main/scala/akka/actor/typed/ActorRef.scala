/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import akka.annotation.InternalApi
import akka.{ actor ⇒ a }

import scala.annotation.unchecked.uncheckedVariance
import language.implicitConversions
import scala.concurrent.Future
import scala.util.Success

/**
 * An ActorRef is the identity or address of an Actor instance. It is valid
 * only during the Actor’s lifetime and allows messages to be sent to that
 * Actor instance. Sending a message to an Actor that has terminated before
 * receiving the message will lead to that message being discarded; such
 * messages are delivered to the [[DeadLetter]] channel of the
 * [[akka.typed.EventStream]] on a best effort basis
 * (i.e. this delivery is not reliable).
 */
trait ActorRef[-T] extends java.lang.Comparable[ActorRef[_]] {
  /**
   * Send a message to the Actor referenced by this ActorRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: T): Unit

  /**
   * Narrow the type of this `ActorRef`, which is always a safe operation.
   */
  def narrow[U <: T]: ActorRef[U]

  /**
   * Unsafe utility method for widening the type accepted by this ActorRef;
   * provided to avoid having to use `asInstanceOf` on the full reference type,
   * which would unfortunately also work on non-ActorRefs.
   */
  def upcast[U >: T @uncheckedVariance]: ActorRef[U]

  /**
   * The hierarchical path name of the referenced Actor. The lifecycle of the
   * ActorRef is fully contained within the lifecycle of the [[akka.actor.ActorPath]]
   * and more than one Actor instance can exist with the same path at different
   * points in time, but not concurrently.
   */
  def path: a.ActorPath

}

object ActorRef {

  implicit final class ActorRefOps[-T](val ref: ActorRef[T]) extends AnyVal {
    /**
     * Send a message to the Actor referenced by this ActorRef using *at-most-once*
     * messaging semantics.
     */
    def !(msg: T): Unit = ref.tell(msg)
  }

  // FIXME factory methods for below for Java (trait + object)

  /**
   * INTERNAL API
   *
   * FIXME, this isn't really used since we removed the native actor system
   */
  @InternalApi private[akka] def apply[T](f: Future[ActorRef[T]], bufferSize: Int = 1000): ActorRef[T] =
    f.value match {
      // an AdaptedActorSystem will always create refs eagerly, so it will take this path
      case Some(Success(ref)) ⇒ ref
      case _                  ⇒ throw new IllegalStateException("Only expecting completed futures until the native actor system is implemented")
    }
}
