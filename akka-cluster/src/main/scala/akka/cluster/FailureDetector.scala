/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Actor
import akka.cluster._
import akka.dispatch.Dispatchers
import akka.util.ListenerManagement

import scala.collection.mutable.{ HashMap, Set }

import java.net.InetSocketAddress

object FailureDetector {

  private sealed trait FailureDetectorEvent
  private case class Register(strategy: FailOverStrategy, address: InetSocketAddress) extends FailureDetectorEvent
  private case class Unregister(strategy: FailOverStrategy, address: InetSocketAddress) extends FailureDetectorEvent

  private[akka] val registry = Actor.localActorOf[Registry].start()

  def register(strategy: FailOverStrategy, address: InetSocketAddress) = registry ! Register(strategy, address)

  def unregister(strategy: FailOverStrategy, address: InetSocketAddress) = registry ! Unregister(strategy, address)

  private class Registry extends Actor {
    self.dispatcher = Dispatchers.newPinnedDispatcher(self)

    val strategies = new HashMap[InetSocketAddress, Set[FailOverStrategy]]() {
      override def default(k: InetSocketAddress) = Set.empty[FailOverStrategy]
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

  trait FailOverStrategy {

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

    def remoteClientWriteFailed(request: AnyRef, cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {}

    def remoteClientError(cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {}

    def remoteClientDisconnected(client: RemoteClientModule, address: InetSocketAddress) {}

    def remoteClientShutdown(client: RemoteClientModule, address: InetSocketAddress) {}

    def remoteServerWriteFailed(request: AnyRef, cause: Throwable, server: RemoteServerModule, clientAddress: Option[InetSocketAddress]) {}

    def remoteServerError(cause: Throwable, server: RemoteServerModule) {}

    def remoteServerShutdown(server: RemoteServerModule) {}
  }

  trait RemoveConnectionOnFirstFailureFailOverStrategy extends FailOverStrategy {

    override def remoteClientWriteFailed(request: AnyRef, cause: Throwable, client: RemoteClientModule, address: InetSocketAddress) {
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

    private def removeConnection(address: InetSocketAddress) = {} //connections.get(address) foreach (remove(connection))
  }

  trait LinearBackoffFailOverStrategy extends FailOverStrategy {
  }

  trait ExponentialBackoffFailOverStrategy extends FailOverStrategy {
  }

  trait CircuitBreakerFailOverStrategy extends FailOverStrategy {
  }
}
