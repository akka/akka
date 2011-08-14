package akka.cluster

/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.routing.RouterType
import RouterType._

import com.eaio.uuid.UUID

import java.net.InetSocketAddress
import akka.routing.{ RandomRouter, DirectRouter, RoundRobinRouter }

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Routing {

  def newRouter(
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
}
