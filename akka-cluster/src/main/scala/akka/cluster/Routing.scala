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

    def route(message: Any)(implicit sender: Option[ActorRef])

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T]
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Direct extends Router {
    lazy val connection: Option[ActorRef] = {
      if (connections.isEmpty) throw new IllegalStateException("DirectRouter need a single replica connection found [0]")
      connections.values.headOption
    }

    def route(message: Any)(implicit sender: Option[ActorRef]) {
      if (connection.isDefined) connection.get.!(message)(sender)
      else throw new RoutingException("No node connections for router")
    }

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T] =
      if (connection.isDefined) connection.get.!!!(message, timeout)(sender)
      else throw new RoutingException("No node connections for router")
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Random extends Router {
    private val random = new java.util.Random(System.currentTimeMillis)

    def route(message: Any)(implicit sender: Option[ActorRef]) {
      if (next.isDefined) next.get.!(message)(sender)
      else throw new RoutingException("No node connections for router")
    }

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T] =
      if (next.isDefined) next.get.!!!(message, timeout)(sender)
      else throw new RoutingException("No node connections for router")

    private def next: Option[ActorRef] =
      if (connections.isEmpty) None
      else Some(connections.valuesIterator.drop(random.nextInt(connections.size)).next)
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait RoundRobin extends Router {
    private def items: List[ActorRef] = connections.values.toList

    private var current = items

    def route(message: Any)(implicit sender: Option[ActorRef]) {
      if (next.isDefined) next.get.!(message)(sender)
      else throw new RoutingException("No node connections for router")
    }

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T] =
      if (next.isDefined) next.get.!!!(message, timeout)(sender)
      else throw new RoutingException("No node connections for router")

    private def hasNext = items != Nil

    private def next: Option[ActorRef] = synchronized {
      val rest = if (current == Nil) items else current
      current = rest.tail
      rest.headOption
    }
  }
}
