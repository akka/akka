/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import scala.collection.immutable

package object routing {
  /**
   * Routing logic, partial function from (sender, message) to a
   * set of destinations.
   */
  type Route = PartialFunction[(akka.actor.ActorRef, Any), immutable.Iterable[Destination]]
}
