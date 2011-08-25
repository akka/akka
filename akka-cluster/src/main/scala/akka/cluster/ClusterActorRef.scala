/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor._
import akka.util._
import akka.event.EventHandler
import ReflectiveAccess._
import akka.dispatch.Future
import akka.routing._
import RouterType._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.eaio.uuid.UUID

import collection.immutable.Map
import annotation.tailrec

/**
 * ClusterActorRef factory and locator.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ClusterActorRef {

  def newRef(
    routerType: RouterType,
    inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
    actorAddress: String,
    timeout: Long): ClusterActorRef = {
    routerType match {
      case Direct        ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout, new DirectRouter())
      case Random        ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout, new RandomRouter())
      case RoundRobin    ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout, new RoundRobinRouter())
      case LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
      case LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
      case LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
    }
  }

  /**
   * Finds the cluster actor reference that has a specific address.
   */
  def actorFor(address: String): Option[ActorRef] = Actor.registry.local.actorFor(Address.clusterActorRefPrefix + address)
}

/**
 * ActorRef representing a one or many instances of a clustered, load-balanced and sometimes replicated actor
 * where the instances can reside on other nodes in the cluster.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterActorRef private[akka] (inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
                                     _address: String,
                                     _timeout: Long,
                                     val router: Router)
  extends UnsupportedActorRef {

  ClusterModule.ensureEnabled()

  //  val address = Address.clusterActorRefPrefix + _address
  val address = _address

  timeout = _timeout

  val connections = new ClusterActorRefConnections((Map[InetSocketAddress, ActorRef]() /: inetSocketAddresses) {
    case (map, (uuid, inetSocketAddress)) ⇒ map + (inetSocketAddress -> createRemoteActorRef(address, inetSocketAddress))
  })

  router.init(connections)

  def connectionsSize(): Int = connections.size

  override def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    router.route(message)(sender)
  }

  override def postMessageToMailboxAndCreateFutureResultWithTimeout(message: Any,
                                                                    timeout: Timeout,
                                                                    channel: UntypedChannel): Future[Any] = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    router.route[Any](message, timeout.duration.toMillis)(sender)
  }

  private def createRemoteActorRef(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(inetSocketAddress, actorAddress, Actor.TIMEOUT, None)
  }

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

  class ClusterActorRefConnections() extends RouterConnections {

    private val state = new AtomicReference[State]()

    def this(connectionMap: Map[InetSocketAddress, ActorRef]) = {
      this()
      state.set(new State(Long.MinValue, connectionMap))
    }

    def version: Long = state.get().version

    def versionedIterator = {
      val s = state.get
      (s.version, s.connections.values)
    }

    def size: Int = state.get().connections.size

    def stopAll() {
      state.get().connections.values foreach (_.stop()) // shut down all remote connections
    }

    @tailrec
    final def failOver(from: InetSocketAddress, to: InetSocketAddress): Unit = {
      EventHandler.debug(this, "ClusterActorRef. %s failover from %s to %s".format(address, from, to))

      val oldState = state.get
      var change = false
      val newMap = oldState.connections map {
        case (`from`, actorRef) ⇒
          change = true
          //          actorRef.stop()
          (to, createRemoteActorRef(actorRef.address, to))
        case other ⇒ other
      }

      if (change) {
        //there was a state change, so we are now going to update the state.
        val newState = new State(oldState.version + 1, newMap)

        //if we are not able to update, the state, we are going to try again.
        if (!state.compareAndSet(oldState, newState)) failOver(from, to)
      }
    }

    @tailrec
    final def signalDeadActor(deadRef: ActorRef) = {
      EventHandler.debug(this, "ClusterActorRef. %s signalDeadActor %s".format(uuid, deadRef.uuid))

      val oldState = state.get()

      //remote the ref from the connections.
      var newConnections = Map[InetSocketAddress, ActorRef]()
      oldState.connections.keys.foreach(
        address ⇒ {
          val actorRef: ActorRef = oldState.connections.get(address).get
          if (actorRef ne deadRef) newConnections = newConnections + ((address, actorRef))
        })

      if (newConnections.size != oldState.connections.size) {
        //one or more occurrances of the actorRef were removed, so we need to update the state.
        val newState = new State(oldState.version + 1, newConnections)

        //if we are not able to update the state, we just try again.
        if (!state.compareAndSet(oldState, newState)) signalDeadActor(deadRef)
      }
    }

    case class State(val version: Long, val connections: Map[InetSocketAddress, ActorRef])
  }
}
