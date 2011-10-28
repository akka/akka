/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.actor._

import scala.annotation.tailrec

import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }
import java.net.InetSocketAddress

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
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
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
   * Returns a VersionedIterator containing all connectected ActorRefs at some moment in time. Since there is
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

  /**
   * Creates a new connection (ActorRef) if it didn't exist. Atomically.
   */
  def putIfAbsent(address: InetSocketAddress, newConnectionFactory: () ⇒ ActorRef): ActorRef

  /**
   * Fails over connections from one address to another.
   */
  def failOver(from: InetSocketAddress, to: InetSocketAddress)
}

/**
 * Manages local connections for a router, e.g. local actors.
 */
class LocalConnectionManager(initialConnections: Iterable[ActorRef]) extends ConnectionManager {

  case class State(version: Long, connections: Iterable[ActorRef]) extends VersionedIterable[ActorRef] {
    def iterable = connections
  }

  private val state: AtomicReference[State] = new AtomicReference[State](newState())

  private def newState() = State(Long.MinValue, initialConnections)

  def version: Long = state.get.version

  def size: Int = state.get.connections.size

  def isEmpty: Boolean = state.get.connections.isEmpty

  def connections = state.get

  def shutdown() {
    state.get.connections foreach (_.stop())
  }

  @tailrec
  final def remove(ref: ActorRef) = {
    val oldState = state.get

    //remote the ref from the connections.
    var newList = oldState.connections.filter(currentActorRef ⇒ currentActorRef ne ref)

    if (newList.size != oldState.connections.size) {
      //one or more occurrences of the actorRef were removed, so we need to update the state.

      val newState = State(oldState.version + 1, newList)
      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) remove(ref)
    }
  }

  def failOver(from: InetSocketAddress, to: InetSocketAddress) {} // do nothing here

  def putIfAbsent(address: InetSocketAddress, newConnectionFactory: () ⇒ ActorRef): ActorRef = {
    throw new UnsupportedOperationException("Not supported")
  }
}
