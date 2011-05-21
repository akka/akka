/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import Cluster._

import akka.actor._
import akka.actor.Actor._
import akka.actor.RouterType._
import akka.dispatch.Future
import akka.AkkaException

import java.net.InetSocketAddress

import com.eaio.uuid.UUID

class RoutingException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Router {
  def newRouter(
    routerType: RouterType,
    addresses: Array[Tuple2[UUID, InetSocketAddress]],
    serviceId: String,
    timeout: Long,
    replicationStrategy: ReplicationStrategy = ReplicationStrategy.WriteThrough): ClusterActorRef = {

    routerType match {
      case Direct ⇒ new ClusterActorRef(
        addresses, serviceId, timeout,
        replicationStrategy) with Direct

      case Random ⇒ new ClusterActorRef(
        addresses, serviceId, timeout,
        replicationStrategy) with Random

      case RoundRobin ⇒ new ClusterActorRef(
        addresses, serviceId, timeout,
        replicationStrategy) with RoundRobin

      case LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
      case LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
      case LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Router {
    def connections: Map[InetSocketAddress, ActorRef]

    def route(message: Any)(implicit sender: Option[ActorRef]): Unit

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T]
  }

  trait BasicRouter extends Router {
    def route(message: Any)(implicit sender: Option[ActorRef]): Unit = next match {
      case Some(actor) ⇒ actor.!(message)(sender)
      case _           ⇒ throw new RoutingException("No node connections for router")
    }

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T] = next match {
      case Some(actor) ⇒ actor.!!!(message, timeout)(sender)
      case _           ⇒ throw new RoutingException("No node connections for router")
    }

    protected def next: Option[ActorRef]
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Direct extends BasicRouter {
    lazy val next: Option[ActorRef] = connections.values.headOption
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Random extends BasicRouter {
    private val random = new java.util.Random(System.currentTimeMillis)

    def next: Option[ActorRef] =
      if (connections.isEmpty) None
      else Some(connections.valuesIterator.drop(random.nextInt(connections.size)).next)
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait RoundRobin extends BasicRouter {
    private def items: List[ActorRef] = connections.values.toList

    private var current = items

    private def hasNext = items != Nil

    def next: Option[ActorRef] = synchronized {
      val rest = if (current == Nil) items else current
      current = rest.tail
      rest.headOption
    }
  }
}
