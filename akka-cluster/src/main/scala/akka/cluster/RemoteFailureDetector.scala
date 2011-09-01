/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Actor, ActorRef, Props }
import Actor._
import akka.cluster._
import akka.routing._
import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future, PinnedDispatcher }
import akka.util.ListenerManagement

import scala.collection.mutable.{ HashMap, Set }
import scala.annotation.tailrec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

object RemoteFailureDetector {

  private sealed trait FailureDetectorEvent
  private case class Register(strategy: RemoteFailureListener, address: InetSocketAddress) extends FailureDetectorEvent
  private case class Unregister(strategy: RemoteFailureListener, address: InetSocketAddress) extends FailureDetectorEvent

  private[akka] val registry = actorOf(Props(new Registry).copy(dispatcher = new PinnedDispatcher(), localOnly = true))

  def register(strategy: RemoteFailureListener, address: InetSocketAddress) = registry ! Register(strategy, address)

  def unregister(strategy: RemoteFailureListener, address: InetSocketAddress) = registry ! Unregister(strategy, address)

  private class Registry extends Actor {

    val strategies = new HashMap[InetSocketAddress, Set[RemoteFailureListener]]() {
      override def default(k: InetSocketAddress) = Set.empty[RemoteFailureListener]
    }

    def receive = {
      case event: RemoteClientLifeCycleEvent ⇒
        strategies(event.remoteAddress) foreach (_ notify event)

      case event: RemoteServerLifeCycleEvent ⇒ // FIXME handle RemoteServerLifeCycleEvent

      case Register(strategy, address) ⇒
        strategies(address) += strategy

      case Unregister(strategy, address) ⇒
        strategies(address) -= strategy

      case _ ⇒ //ignore other
    }
  }
}

abstract class RemoteFailureDetectorBase(initialConnections: Map[InetSocketAddress, ActorRef]) extends FailureDetector {
  import ClusterActorRef._

  case class State(val version: Long = Integer.MIN_VALUE, val connections: Map[InetSocketAddress, ActorRef]) extends VersionedIterable[ActorRef] {
    def iterable: Iterable[ActorRef] = connections.values
  }

  //  type C

  private val state = new AtomicReference[State]()

  state.set(State(Long.MinValue, initialConnections))

  def version: Long = state.get().version

  def versionedIterable = state.get

  def size: Int = state.get.connections.size

  def connections: Map[InetSocketAddress, ActorRef] = state.get.connections

  def stopAll() {
    state.get().iterable foreach (_.stop()) // shut down all remote connections
  }

  @tailrec
  final def failOver(from: InetSocketAddress, to: InetSocketAddress) {
    EventHandler.debug(this, "ClusterActorRef failover from [%s] to [%s]".format(from, to))

    val oldState = state.get
    var changed = false
    val newMap = oldState.connections map {
      case (`from`, actorRef) ⇒
        changed = true
        //actorRef.stop()
        (to, createRemoteActorRef(actorRef.address, to))
      case other ⇒ other
    }

    if (changed) {
      //there was a state change, so we are now going to update the state.
      val newState = State(oldState.version + 1, newMap)

      //if we are not able to update, the state, we are going to try again.
      if (!state.compareAndSet(oldState, newState)) failOver(from, to)
    }
  }

  @tailrec
  final def remove(faultyConnection: ActorRef) {
    EventHandler.debug(this, "ClusterActorRef remove [%s]".format(faultyConnection.uuid))

    val oldState = state.get()

    var changed = false

    //remote the faultyConnection from the clustered-connections.
    var newConnections = Map.empty[InetSocketAddress, ActorRef]
    oldState.connections.keys foreach { address ⇒
      val actorRef: ActorRef = oldState.connections.get(address).get
      if (actorRef ne faultyConnection) {
        newConnections = newConnections + ((address, actorRef))
      } else {
        changed = true
      }
    }

    if (changed) {
      //one or more occurrances of the actorRef were removed, so we need to update the state.
      val newState = State(oldState.version + 1, newConnections)

      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) remove(faultyConnection)
    }
  }
}

trait RemoteFailureListener {

  def notify(event: RemoteLifeCycleEvent) = event match {
    case RemoteClientWriteFailed(request, cause, client, address) ⇒
      remoteClientWriteFailed(request, cause, client, address)
      println("--------->>> RemoteClientWriteFailed")
    case RemoteClientError(cause, client, address) ⇒
      println("--------->>> RemoteClientError")
      remoteClientError(cause, client, address)
    case RemoteClientDisconnected(client, address) ⇒
      remoteClientDisconnected(client, address)
      println("--------->>> RemoteClientDisconnected")
    case RemoteClientShutdown(client, address) ⇒
      remoteClientShutdown(client, address)
      println("--------->>> RemoteClientShutdown")
    case RemoteServerWriteFailed(request, cause, server, clientAddress) ⇒
      remoteServerWriteFailed(request, cause, server, clientAddress)
    case RemoteServerError(cause, server) ⇒
      remoteServerError(cause, server)
    case RemoteServerShutdown(server) ⇒
      remoteServerShutdown(server)
  }

  def remoteClientWriteFailed(
    request: AnyRef, cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {}

  def remoteClientError(cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {}

  def remoteClientDisconnected(client: RemoteClientModule, address: InetSocketAddress) {}

  def remoteClientShutdown(client: RemoteClientModule, address: InetSocketAddress) {}

  def remoteServerWriteFailed(
    request: AnyRef, cause: Throwable, server: RemoteServerModule, clientAddress: Option[InetSocketAddress]) {}

  def remoteServerError(cause: Throwable, server: RemoteServerModule) {}

  def remoteServerShutdown(server: RemoteServerModule) {}
}

class RemoveConnectionOnFirstFailureRemoteFailureDetector(initialConnections: Map[InetSocketAddress, ActorRef])
  extends RemoteFailureDetectorBase(initialConnections)
  with RemoteFailureListener {

  override def remoteClientWriteFailed(
    request: AnyRef, cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {
    removeConnection(address)
  }

  override def remoteClientError(cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {
    removeConnection(address)
  }

  override def remoteClientDisconnected(client: RemoteClientModule, address: InetSocketAddress) {
    removeConnection(address)
  }

  override def remoteClientShutdown(client: RemoteClientModule, address: InetSocketAddress) {
    removeConnection(address)
  }

  private def removeConnection(address: InetSocketAddress) =
    connections.get(address) foreach { connection ⇒ remove(connection) }
}

trait LinearBackoffRemoteFailureListener extends RemoteFailureListener {
}

trait ExponentialBackoffRemoteFailureListener extends RemoteFailureListener {
}

trait CircuitBreakerRemoteFailureListener extends RemoteFailureListener {
}