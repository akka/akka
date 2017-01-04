/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.{ actor ⇒ a }
import scala.annotation.unchecked.uncheckedVariance
import language.implicitConversions
import scala.concurrent.Future

/**
 * An ActorRef is the identity or address of an Actor instance. It is valid
 * only during the Actor’s lifetime and allows messages to be sent to that
 * Actor instance. Sending a message to an Actor that has terminated before
 * receiving the message will lead to that message being discarded; such
 * messages are delivered to the [[akka.actor.DeadLetter]] channel of the
 * [[akka.event.EventStream]] on a best effort basis
 * (i.e. this delivery is not reliable).
 */
abstract class ActorRef[-T](_path: a.ActorPath) extends java.lang.Comparable[ActorRef[Nothing]] { this: internal.ActorRefImpl[T] ⇒
  /**
   * Send a message to the Actor referenced by this ActorRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: T): Unit

  /**
   * Send a message to the Actor referenced by this ActorRef using *at-most-once*
   * messaging semantics.
   */
  def !(msg: T): Unit = tell(msg)

  /**
   * Unsafe utility method for widening the type accepted by this ActorRef;
   * provided to avoid having to use `asInstanceOf` on the full reference type,
   * which would unfortunately also work on non-ActorRefs.
   */
  def upcast[U >: T @uncheckedVariance]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  /**
   * The hierarchical path name of the referenced Actor. The lifecycle of the
   * ActorRef is fully contained within the lifecycle of the [[akka.actor.ActorPath]]
   * and more than one Actor instance can exist with the same path at different
   * points in time, but not concurrently.
   */
  final val path: a.ActorPath = _path

  /**
   * Comparison takes path and the unique id of the actor cell into account.
   */
  final override def compareTo(other: ActorRef[Nothing]) = {
    val x = this.path compareTo other.path
    if (x == 0) if (this.path.uid < other.path.uid) -1 else if (this.path.uid == other.path.uid) 0 else 1
    else x
  }

  final override def hashCode: Int = path.uid

  /**
   * Equals takes path and the unique id of the actor cell into account.
   */
  final override def equals(that: Any): Boolean = that match {
    case other: ActorRef[_] ⇒ path.uid == other.path.uid && path == other.path
    case _                  ⇒ false
  }

  final override def toString: String = s"Actor[${path}#${path.uid}]"
}

object ActorRef {
  /**
   * Create an ActorRef from a Future, buffering up to the given number of
   * messages in while the Future is not fulfilled.
   */
  def apply[T](f: Future[ActorRef[T]], bufferSize: Int = 1000): ActorRef[T] = new internal.FutureRef(FuturePath, bufferSize, f)

  /**
   * Create an ActorRef by providing a function that is invoked for sending
   * messages and a termination callback.
   */
  def apply[T](send: (T, internal.FunctionRef[T]) ⇒ Unit, terminate: internal.FunctionRef[T] ⇒ Unit): ActorRef[T] =
    new internal.FunctionRef(FunctionPath, send, terminate)

  private[typed] val FuturePath = a.RootActorPath(a.Address("akka.typed.internal", "future"))
  private[typed] val FunctionPath = a.RootActorPath(a.Address("akka.typed.internal", "function"))
}
