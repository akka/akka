/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import Cluster._

import akka.actor._
import Actor._
import akka.dispatch.Future
import akka.event.EventHandler
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
    timeout: Long): ClusterActorRef = {
    routerType match {
      case Direct        ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout) with Direct
      case Random        ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout) with Random
      case RoundRobin    ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout) with RoundRobin
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
      case _           ⇒ throwNoConnectionsError()
    }

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T] = next match {
      case Some(actor) ⇒ actor.?(message, timeout)(sender).asInstanceOf[Future[T]]
      case _           ⇒ throwNoConnectionsError()
    }

    protected def next: Option[ActorRef]

    private def throwNoConnectionsError() = {
      val error = new RoutingException("No replica connections for router")
      EventHandler.error(error, this, error.toString)
      throw error
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Direct extends BasicRouter {
    lazy val next: Option[ActorRef] = {
      val connection = connections.values.headOption
      if (connection.isEmpty) EventHandler.warning(this, "Router has no replica connection")
      connection
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Random extends BasicRouter {
    private val random = new java.util.Random(System.currentTimeMillis)

    def next: Option[ActorRef] =
      if (connections.isEmpty) {
        EventHandler.warning(this, "Router has no replica connections")
        None
      } else Some(connections.valuesIterator.drop(random.nextInt(connections.size)).next)
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

        if (newItems.isEmpty) {
          EventHandler.warning(this, "Router has no replica connections")
          None
        } else {
          if (current.compareAndSet(currentItems, newItems.tail)) newItems.headOption
          else findNext
        }
      }

      findNext
    }
  }
}
