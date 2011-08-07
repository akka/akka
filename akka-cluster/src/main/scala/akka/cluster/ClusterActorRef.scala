/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor._
import akka.util._
import ReflectiveAccess._
import akka.dispatch.Future

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.eaio.uuid.UUID
import collection.immutable.Map
import annotation.tailrec
import akka.routing.Router
import akka.event.EventHandler

/**
 * ActorRef representing a one or many instances of a clustered, load-balanced and sometimes replicated actor
 * where the instances can reside on other nodes in the cluster.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterActorRef private[akka] (inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
                                     val address: String,
                                     _timeout: Long)
  extends UnsupportedActorRef {
  this: Router ⇒
  timeout = _timeout

  private[akka] val inetSocketAddressToActorRefMap = new AtomicReference[Map[InetSocketAddress, ActorRef]](
    (Map[InetSocketAddress, ActorRef]() /: inetSocketAddresses) {
      case (map, (uuid, inetSocketAddress)) ⇒ map + (inetSocketAddress -> createRemoteActorRef(address, inetSocketAddress))
    })

  ClusterModule.ensureEnabled()

  def connections: Iterable[ActorRef] = inetSocketAddressToActorRefMap.get.values

  override def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    route(message)(sender)
  }

  override def postMessageToMailboxAndCreateFutureResultWithTimeout(message: Any,
                                                                    timeout: Timeout,
                                                                    channel: UntypedChannel): Future[Any] = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    route[Any](message, timeout.duration.toMillis)(sender)
  }

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress): Unit = {
    EventHandler.debug(this, "ClusterActorRef. %s failover from %s to %s".format(address, from, to))

    @tailrec
    def doFailover(from: InetSocketAddress, to: InetSocketAddress): Unit = {
      val oldValue = inetSocketAddressToActorRefMap.get

      val newValue = oldValue map {
        case (`from`, actorRef) ⇒
          actorRef.stop()
          (to, createRemoteActorRef(actorRef.address, to))
        case other ⇒ other
      }

      if (!inetSocketAddressToActorRefMap.compareAndSet(oldValue, newValue))
        doFailover(from, to)
    }

    doFailover(from, to)
  }

  /**
   * Removes the given address (and the corresponding actorref) from this ClusteredActorRef.
   *
   * Call can safely be made when the address is missing.
   *
   * Call is threadsafe.
   */
  @tailrec
  private def remove(address: InetSocketAddress): Unit = {
    val oldValue = inetSocketAddressToActorRefMap.get()

    var newValue = oldValue - address

    if (!inetSocketAddressToActorRefMap.compareAndSet(oldValue, newValue))
      remove(address)
  }

  def signalDeadActor(ref: ActorRef): Unit = {
    EventHandler.debug(this, "ClusterActorRef %s signalDeadActor %s".format(address, ref.address))

    //since the number remote actor refs for a clustered actor ref is quite low, we can deal with the O(N) complexity
    //of the following removal.
    val map = inetSocketAddressToActorRefMap.get
    val it = map.keySet.iterator

    while (it.hasNext) {
      val address = it.next()
      val foundRef: ActorRef = map.get(address).get

      if (foundRef == ref) {
        remove(address)
        return
      }
    }
  }

  private def createRemoteActorRef(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(inetSocketAddress, actorAddress, Actor.TIMEOUT, None)
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
        inetSocketAddressToActorRefMap.get.values foreach (_.stop()) // shut down all remote connections
      }
    }
  }
}
