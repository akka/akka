/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.routing._
import akka.AkkaApplication

import scala.collection.immutable.Map
import scala.annotation.tailrec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Remote connection manager, manages remote connections, e.g. RemoteActorRef's.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteConnectionManager(
  app: AkkaApplication,
  remote: Remote,
  initialConnections: Map[InetSocketAddress, ActorRef] = Map.empty[InetSocketAddress, ActorRef])
  extends ConnectionManager {

  // FIXME is this VersionedIterable really needed? It is not used I think. Complicates API. See 'def connections' etc.
  case class State(version: Long, connections: Map[InetSocketAddress, ActorRef])
    extends VersionedIterable[ActorRef] {
    def iterable: Iterable[ActorRef] = connections.values
  }

  val failureDetector = remote.failureDetector

  private val state: AtomicReference[State] = new AtomicReference[State](newState())

  // register all initial connections - e.g listen to events from them
  initialConnections.keys foreach (remote.eventStream.register(failureDetector, _))

  /**
   * This method is using the FailureDetector to filter out connections that are considered not available.
   */
  private def filterAvailableConnections(current: State): State = {
    val availableConnections = current.connections filter { entry ⇒ failureDetector.isAvailable(entry._1) }
    current copy (version = current.version, connections = availableConnections)
  }

  private def newState() = State(Long.MinValue, initialConnections)

  def version: Long = state.get.version

  // FIXME should not return State value but a Seq with connections
  def connections = filterAvailableConnections(state.get)

  def size: Int = connections.connections.size

  def connectionFor(address: InetSocketAddress): Option[ActorRef] = connections.connections.get(address)

  def shutdown() {
    state.get.iterable foreach (_.stop()) // shut down all remote connections
  }

  @tailrec
  final def failOver(from: InetSocketAddress, to: InetSocketAddress) {
    app.eventHandler.debug(this, "Failing over connection from [%s] to [%s]".format(from, to))

    val oldState = state.get
    var changed = false

    val newMap = oldState.connections map {
      case (`from`, actorRef) ⇒
        changed = true
        //actorRef.stop()
        (to, newConnection(actorRef.address, to))
      case other ⇒ other
    }

    if (changed) {
      //there was a state change, so we are now going to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newMap)

      //if we are not able to update, the state, we are going to try again.
      if (!state.compareAndSet(oldState, newState)) {
        failOver(from, to) // recur
      }
    }
  }

  @tailrec
  final def remove(faultyConnection: ActorRef) {

    val oldState = state.get()
    var changed = false

    var faultyAddress: InetSocketAddress = null
    var newConnections = Map.empty[InetSocketAddress, ActorRef]

    oldState.connections.keys foreach { address ⇒
      val actorRef: ActorRef = oldState.connections.get(address).get
      if (actorRef ne faultyConnection) {
        newConnections = newConnections + ((address, actorRef))
      } else {
        faultyAddress = address
        changed = true
      }
    }

    if (changed) {
      //one or more occurrances of the actorRef were removed, so we need to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) {
        remove(faultyConnection) // recur
      } else {
        app.eventHandler.debug(this, "Removing connection [%s]".format(faultyAddress))
        remote.eventStream.unregister(failureDetector, faultyAddress) // unregister the connections - e.g stop listen to events from it
      }
    }
  }

  @tailrec
  final def putIfAbsent(address: InetSocketAddress, newConnectionFactory: () ⇒ ActorRef): ActorRef = {

    val oldState = state.get()
    val oldConnections = oldState.connections

    oldConnections.get(address) match {
      case Some(connection) ⇒ connection // we already had the connection, return it
      case None ⇒ // we need to create it
        val newConnection = newConnectionFactory()
        val newConnections = oldConnections + (address -> newConnection)

        //one or more occurrances of the actorRef were removed, so we need to update the state.
        val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

        //if we are not able to update the state, we just try again.
        if (!state.compareAndSet(oldState, newState)) {
          // we failed, need compensating action
          newConnection.stop() // stop the new connection actor and try again
          putIfAbsent(address, newConnectionFactory) // recur
        } else {
          // we succeeded
          app.eventHandler.debug(this, "Adding connection [%s]".format(address))
          remote.eventStream.register(failureDetector, address) // register the connection - e.g listen to events from it
          newConnection // return new connection actor
        }
    }
  }

  private[remote] def newConnection(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(remote.server, inetSocketAddress, actorAddress, None)
  }
}
