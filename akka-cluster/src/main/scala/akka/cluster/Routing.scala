package akka.cluster

/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.routing.RouterType._
import akka.routing._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@deprecated("will be removed as soon as the RoutingProps are in place. ")
object Routing {

  def newRouter(routerType: RouterType, actorAddress: String, timeout: Long): ClusterActorRef = {

    val router = routerType match {
      case Direct        ⇒ new DirectRouter()
      case Random        ⇒ new RandomRouter()
      case RoundRobin    ⇒ new RoundRobinRouter()
      case LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
      case LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
      case LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
    }

    val props = new RoutedProps(() ⇒ router, actorAddress, List(), timeout, false)
    new ClusterActorRef(props).start()
  }
}
