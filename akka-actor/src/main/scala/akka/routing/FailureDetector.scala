/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.AkkaException
import akka.actor._
import akka.event.EventHandler
import akka.config.ConfigurationException
import akka.actor.UntypedChannel._
import akka.dispatch.{ Future, Futures }
import akka.util.ReflectiveAccess

import java.net.InetSocketAddress
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }

import scala.annotation.tailrec

sealed trait FailureDetectorType

/**
 * Used for declarative configuration of failure detection.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object FailureDetectorType {
  case object RemoveConnectionOnFirstFailureLocalFailureDetector extends FailureDetectorType
  case object RemoveConnectionOnFirstFailureFailureDetector extends FailureDetectorType
  case class BannagePeriodFailureDetector(timeToBan: Long) extends FailureDetectorType
  case class CustomFailureDetector(className: String) extends FailureDetectorType
}

/**
 * Misc helper and factory methods for failure detection.
 */
object FailureDetector {

  def createCustomFailureDetector(
    implClass: String,
    connections: Map[InetSocketAddress, ActorRef]): FailureDetector = {

    ReflectiveAccess.createInstance(
      implClass,
      Array[Class[_]](classOf[Map[InetSocketAddress, ActorRef]]),
      Array[AnyRef](connections)) match {
        case Right(actor) ⇒ actor
        case Left(exception) ⇒
          val cause = exception match {
            case i: InvocationTargetException ⇒ i.getTargetException
            case _                            ⇒ exception
          }
          throw new ConfigurationException(
            "Could not instantiate custom FailureDetector of [" +
              implClass + "] due to: " +
              cause, cause)
      }
  }
}

/**
 * The FailureDetector acts like a middleman between the Router and
 * the actor reference that does the routing and can dectect and act upon failure.
 *
 * Through the FailureDetector:
 * <ol>
 *   <li>
 *     the actor ref can signal that something has changed in the known set of connections. The Router can see
 *     when a changed happened (by checking the version) and update its internal datastructures.
 *   </li>
 *   <li>
 *      the Router can indicate that some happened happened with a actor ref, e.g. the actor ref dying.
 *   </li>
 * </ol>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait FailureDetector {

  /**
   * Returns true if the 'connection' is considered available.
   */
  def isAvailable(connection: InetSocketAddress): Boolean

  /**
   * Records a successful connection.
   */
  def recordSuccess(connection: InetSocketAddress, timestamp: Long)

  /**
   * Records a failed connection.
   */
  def recordFailure(connection: InetSocketAddress, timestamp: Long)

  /**
   * A version that is useful to see if there is any change in the connections. If there is a change, a router is
   * able to update its internal datastructures.
   */
  def version: Long

  /**
   * Returns the number of connections. Value could be stale as soon as received, and this method can't be combined (easily)
   * with an atomic read of and size and version.
   */
  def size: Int

  /**
   * Stops all managed actors
   */
  def stopAll()

  /**
   * Returns a VersionedIterator containing all connectected ActorRefs at some moment in time. Since there is
   * the time element, also the version is included to be able to read the data (the connections) and the version
   * in an atomic manner.
   *
   * This Iterable is 'persistent'. So it can be handed out to different threads and they see a stable (immutable)
   * view of some set of connections.
   */
  def versionedIterable: VersionedIterable[ActorRef]

  /**
   * A callback that can be used to indicate that a connected actorRef was dead.
   * <p/>
   * Implementations should make sure that this method can be called without the actorRef being part of the
   * current set of connections. The most logical way to deal with this situation, is just to ignore it. One of the
   * reasons this can happen is that multiple thread could at the 'same' moment discover for the same ActorRef that
   * not working.
   *
   * It could be that even after a remove has been called for a specific ActorRef, that the ActorRef
   * is still being used. A good behaving Router will eventually discard this reference, but no guarantees are
   * made how long this takes.
   *
   * @param ref the dead
   */
  def remove(deadRef: ActorRef)

  /**
   * TODO: document
   */
  def putIfAbsent(address: InetSocketAddress, newConnectionFactory: () ⇒ ActorRef): ActorRef

  /**
   * Fails over connections from one address to another.
   */
  def failOver(from: InetSocketAddress, to: InetSocketAddress)
}
