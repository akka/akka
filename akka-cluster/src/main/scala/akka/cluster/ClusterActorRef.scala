/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor._
import akka.util._
import akka.event.EventHandler
import ReflectiveAccess._
import akka.routing._
import RouterType._
import akka.cluster._

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

  def newRef(routerType: RouterType, actorAddress: String, timeout: Long): ClusterActorRef = {

    val routerFactory: () ⇒ Router = routerType match {
      case Direct        ⇒ () ⇒ new DirectRouter
      case Random        ⇒ () ⇒ new RandomRouter()
      case RoundRobin    ⇒ () ⇒ new RoundRobinRouter()
      case LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
      case LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
      case LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
    }

    val props = RoutedProps.apply().withDeployId(actorAddress).withTimeout(timeout).withRouter(routerFactory)
    new ClusterActorRef(props).start()
  }

  /**
   * Finds the cluster actor reference that has a specific address.
   */
  def actorFor(address: String): Option[ActorRef] = Actor.registry.local.actorFor(Address.clusterActorRefPrefix + address)

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

  val connections = new ClusterActorRefConnections((Map[InetSocketAddress, ActorRef]() /: addresses) {
    case (map, (uuid, inetSocketAddress)) ⇒ map + (inetSocketAddress -> createRemoteActorRef(address, inetSocketAddress))
  }, props.connections)

  router.init(connections)

  def connectionsSize(): Int = connections.size

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress): Unit = {
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

class ClusterActorRefConnections() extends RouterConnections {
  import ClusterActorRef._

  private val state = new AtomicReference[State]()

  def this(clusteredConnections: Map[InetSocketAddress, ActorRef], explicitConnections: Iterable[ActorRef]) = {
    this()
    state.set(new State(Long.MinValue, clusteredConnections, explicitConnections))
  }

  def version: Long = state.get().version

  def versionedIterable = state.get

  def size(): Int = state.get().iterable.size

  def stopAll() {
    state.get().clusteredConnections.values foreach (_.stop()) // shut down all remote connections
  }

  @tailrec
  final def failOver(from: InetSocketAddress, to: InetSocketAddress): Unit = {
    EventHandler.debug(this, "ClusterActorRef failover from [%s] to [%s]".format(from, to))

    val oldState = state.get
    var changed = false
    val newMap = oldState.clusteredConnections map {
      case (`from`, actorRef) ⇒
        changed = true
        //actorRef.stop()
        (to, createRemoteActorRef(actorRef.address, to))
      case other ⇒ other
    }

    if (changed) {
      //there was a state change, so we are now going to update the state.
      val newState = new State(oldState.version + 1, newMap, oldState.explicitConnections)

      //if we are not able to update, the state, we are going to try again.
      if (!state.compareAndSet(oldState, newState)) failOver(from, to)
    }
  }

  @tailrec
  final def remove(deadRef: ActorRef) = {
    EventHandler.debug(this, "ClusterActorRef remove [%s]".format(deadRef.uuid))

    val oldState = state.get()

    var changed = false

    //remote the deadRef from the clustered-connections.
    var newConnections = Map[InetSocketAddress, ActorRef]()
    oldState.clusteredConnections.keys.foreach(
      address ⇒ {
        val actorRef: ActorRef = oldState.clusteredConnections.get(address).get
        if (actorRef ne deadRef) {
          newConnections = newConnections + ((address, actorRef))
        } else {
          changed = true
        }
      })

    //remove the deadRef also from the explicit connections.
    var newExplicitConnections = oldState.explicitConnections.filter(
      actorRef ⇒
        if (actorRef == deadRef) {
          changed = true
          false
        } else {
          true
        })

    if (changed) {
      //one or more occurrances of the actorRef were removed, so we need to update the state.
      val newState = new State(oldState.version + 1, newConnections, newExplicitConnections)

      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) remove(deadRef)
    }
  }

  class State(version: Long = Integer.MIN_VALUE,
              val clusteredConnections: Map[InetSocketAddress, ActorRef],
              val explicitConnections: Iterable[ActorRef])
    extends VersionedIterable[ActorRef](version, explicitConnections ++ clusteredConnections.values)
}
