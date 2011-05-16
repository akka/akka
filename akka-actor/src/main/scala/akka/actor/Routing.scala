/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.AkkaException

class RoutingException(message: String) extends AkkaException(message)

sealed trait RouterType

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RouterType {
  object Direct extends RouterType
  object Random extends RouterType
  object RoundRobin extends RouterType
}

// FIXME move all routing in cluster here when we can