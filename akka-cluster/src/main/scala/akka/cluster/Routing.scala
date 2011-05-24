/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import Cluster._

import akka.actor._
import Actor._
import akka.dispatch.Future
import akka.routing.{ RouterType, RoutingException }
import RouterType._

import com.eaio.uuid.UUID

import annotation.tailrec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Router {
  def newRouter(
    routerType: RouterType,
    inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
    actorAddress: String,
    timeout: Long,
    replicationStrategy: ReplicationStrategy = ReplicationStrategy.WriteThrough): ClusterActorRef = {
    routerType match {
      case Direct        ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout, replicationStrategy) with Direct
      case Random        ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout, replicationStrategy) with Random
      case RoundRobin    ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout, replicationStrategy) with RoundRobin
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

    private val current = new AtomicReference[List[ActorRef]](items)

    private def hasNext = connections.nonEmpty

    def next: Option[ActorRef] = {
      @tailrec
      def findNext: Option[ActorRef] = {
        val currentItems = current.get
        val newItems = currentItems match {
          case Nil ⇒ items
          case xs  ⇒ xs
        }

        if (current.compareAndSet(currentItems, newItems.tail)) newItems.headOption
        else findNext
      }

      findNext
    }
  }
}
