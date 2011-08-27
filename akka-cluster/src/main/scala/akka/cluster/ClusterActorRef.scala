/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor._
import akka.util._
import ReflectiveAccess._
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import collection.immutable.Map
import annotation.tailrec
import akka.event.EventHandler
import akka.routing._

/**
 * ActorRef representing a one or many instances of a clustered, load-balanced and sometimes replicated actor
 * where the instances can reside on other nodes in the cluster.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class ClusterActorRef(props: RoutedProps) extends AbstractRoutedActorRef(props) {

  ClusterModule.ensureEnabled()

  val addresses = Cluster.node.addressesForActor(address)
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

  private def createRemoteActorRef(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(inetSocketAddress, actorAddress, Actor.TIMEOUT, None)
  }

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress): Unit = {
    connections.failOver(from, to)
  }

  def start(): this.type = synchronized[this.type] {
    if (_status == ActorRefInternals.UNSTARTED) {
      _status = ActorRefInternals.RUNNING
      //TODO add this? Actor.registry.register(this)
    }
    this
  }

  def stop() {
    synchronized {
      if (_status == ActorRefInternals.RUNNING) {
        //TODO add this? Actor.registry.unregister(this)
        _status = ActorRefInternals.SHUTDOWN
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)

        // FIXME here we need to fire off Actor.cluster.remove(address) (which needs to be properly implemented first, see ticket)
        connections.stopAll()
      }
    }
  }

  class ClusterActorRefConnections() extends RouterConnections {

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
      EventHandler.debug(this, "ClusterActorRef. %s failover from %s to %s".format(address, from, to))

      val oldState = state.get
      var changed = false
      val newMap = oldState.clusteredConnections map {
        case (`from`, actorRef) ⇒
          changed = true
          actorRef.stop()
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
    final def signalDeadActor(deadRef: ActorRef) = {
      EventHandler.debug(this, "ClusterActorRef. %s signalDeadActor %s".format(uuid, deadRef.uuid))

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
        if (!state.compareAndSet(oldState, newState)) signalDeadActor(deadRef)
      }
    }

    class State(version: Long = Integer.MIN_VALUE,
                val clusteredConnections: Map[InetSocketAddress, ActorRef],
                val explicitConnections: Iterable[ActorRef])
      extends VersionedIterable[ActorRef](version, explicitConnections ++ clusteredConnections.values)

  }

}
