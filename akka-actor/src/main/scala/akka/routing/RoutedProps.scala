/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.actor._
import akka.util.Duration

sealed trait RouterType

/**
 * Used for declarative configuration of Routing.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RouterType {

  object Direct extends RouterType

  /**
   * A RouterType that randomly selects a connection to send a message to.
   */
  object Random extends RouterType

  /**
   * A RouterType that selects the connection by using round robin.
   */
  object RoundRobin extends RouterType

  /**
   * A RouterType that selects the connection by using scatter gather.
   */
  object ScatterGather extends RouterType

  /**
   * A RouterType that selects the connection based on the least amount of cpu usage
   */
  object LeastCPU extends RouterType

  /**
   * A RouterType that select the connection based on the least amount of ram used.
   */
  object LeastRAM extends RouterType

  /**
   * A RouterType that select the connection where the actor has the least amount of messages in its mailbox.
   */
  object LeastMessages extends RouterType

  /**
   * A user-defined custom RouterType.
   */
  case class Custom(implClass: String) extends RouterType
}

/**
 * Contains the configuration to create local and clustered routed actor references.
 * Routed ActorRef configuration object, this is thread safe and fully sharable.
 */
case class RoutedProps private[akka] (
  routerFactory: () ⇒ Router = RoutedProps.defaultRouterFactory,
  connectionManager: ConnectionManager = new LocalConnectionManager(List()),
  timeout: Timeout = RoutedProps.defaultTimeout,
  localOnly: Boolean = RoutedProps.defaultLocalOnly) {
}

object RoutedProps {
  final val defaultTimeout = Timeout(Duration.MinusInf)
  final val defaultRouterFactory = () ⇒ new RoundRobinRouter
  final val defaultLocalOnly = false
}
