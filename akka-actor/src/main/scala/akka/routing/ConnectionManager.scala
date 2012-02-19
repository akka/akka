/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.actor._

/**
 * An Iterable that also contains a version.
 */
trait VersionedIterable[A] {
  val version: Long

  def iterable: Iterable[A]

  def apply(): Iterable[A] = iterable
}

/**
 * Manages connections (ActorRefs) for a router.
 */
trait ConnectionManager {
  /**
   * A version that is useful to see if there is any change in the connections. If there is a change, a router is
   * able to update its internal datastructures.
   */
  def version: Long

  /**
   * Returns the number of 'available' connections. Value could be stale as soon as received, and this method can't be combined (easily)
   * with an atomic read of and size and version.
   */
  def size: Int

  /**
   * Returns if the number of 'available' is 0 or not. Value could be stale as soon as received, and this method can't be combined (easily)
   * with an atomic read of and isEmpty and version.
   */
  def isEmpty: Boolean

  /**
   * Shuts the connection manager down, which stops all managed actors
   */
  def shutdown()

  /**
   * Returns a VersionedIterator containing all connected ActorRefs at some moment in time. Since there is
   * the time element, also the version is included to be able to read the data (the connections) and the version
   * in an atomic manner.
   *
   * This Iterable is 'persistent'. So it can be handed out to different threads and they see a stable (immutable)
   * view of some set of connections.
   */
  def connections: VersionedIterable[ActorRef]

  /**
   * Removes a connection from the connection manager.
   *
   * @param ref the dead
   */
  def remove(deadRef: ActorRef)
}
