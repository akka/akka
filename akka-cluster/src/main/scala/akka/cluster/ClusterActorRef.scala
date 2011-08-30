/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor._
import akka.util._
import akka.event.EventHandler
import ReflectiveAccess._
import akka.routing._
import akka.cluster._
import FailureDetector._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import collection.immutable.Map
import annotation.tailrec

/**
 * ClusterActorRef factory and locator.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ClusterActorRef {
  import FailureDetectorType._
  import RouterType._

  def newRef(
    actorAddress: String,
    routerType: RouterType,
    failureDetectorType: FailureDetectorType,
    timeout: Long): ClusterActorRef = {

    val routerFactory: () ⇒ Router = routerType match {
      case Direct        ⇒ () ⇒ new DirectRouter
      case Random        ⇒ () ⇒ new RandomRouter
      case RoundRobin    ⇒ () ⇒ new RoundRobinRouter
      case LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
      case LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
      case LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
      case Custom        ⇒ sys.error("Router Custom not supported yet")
    }

    val failureDetectorFactory: (Map[InetSocketAddress, ActorRef]) ⇒ FailureDetector = failureDetectorType match {
      case RemoveConnectionOnFirstFailure ⇒
        (connections: Map[InetSocketAddress, ActorRef]) ⇒ new RemoveConnectionOnFirstFailureFailureDetector(connections)
      case _ ⇒
        (connections: Map[InetSocketAddress, ActorRef]) ⇒ new LocalFailureDetector
    }

    new ClusterActorRef(
      RoutedProps()
        .withDeployId(actorAddress)
        .withTimeout(timeout)
        .withRouter(routerFactory)
        .withFailureDetector(failureDetectorFactory)).start()
  }

  /**
   * Finds the cluster actor reference that has a specific address.
   */
  def actorFor(address: String): Option[ActorRef] =
    Actor.registry.local.actorFor(Address.clusterActorRefPrefix + address)

  private[cluster] def createRemoteActorRef(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(inetSocketAddress, actorAddress, Actor.TIMEOUT, None)
  }
}

/**
 * ActorRef representing a one or many instances of a clustered, load-balanced and sometimes replicated actor
 * where the instances can reside on other nodes in the cluster.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class ClusterActorRef(props: RoutedProps) extends AbstractRoutedActorRef(props) {

  import ClusterActorRef._

  ClusterModule.ensureEnabled()

  val addresses = Cluster.node.inetSocketAddressesForActor(address)

  EventHandler.debug(this,
    "Checking out cluster actor ref with address [%s] and router [%s] on [%s] connected to [\n\t%s]"
      .format(address, router, Cluster.node.remoteServerAddress, addresses.map(_._2).mkString("\n\t")))

  addresses foreach {
    case (_, address) ⇒ Cluster.node.clusterActorRefs.put(address, this)
  }

  val connections: FailureDetector = {
    val remoteConnections = (Map[InetSocketAddress, ActorRef]() /: addresses) {
      case (map, (uuid, inetSocketAddress)) ⇒
        map + (inetSocketAddress -> createRemoteActorRef(address, inetSocketAddress))
    }
    props.failureDetectorFactory(remoteConnections)
  }

  router.init(connections)

  def nrOfConnections: Int = connections.size

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress) {
    connections.failOver(from, to)
  }

  def start(): this.type = synchronized[this.type] {
    if (_status == ActorRefInternals.UNSTARTED) {
      _status = ActorRefInternals.RUNNING
      Actor.registry.local.registerClusterActorRef(this)
    }
    this
  }

  def stop() {
    synchronized {
      if (_status == ActorRefInternals.RUNNING) {
        Actor.registry.local.unregisterClusterActorRef(this)
        _status = ActorRefInternals.SHUTDOWN
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)

        // FIXME here we need to fire off Actor.cluster.remove(address) (which needs to be properly implemented first, see ticket)
        connections.stopAll()
      }
    }
  }
}
