/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object routing {

  type Route = PartialFunction[(akka.actor.ActorRef, Any), Iterable[Destination]]

  // To allow pattern matching on the class types
  val RoundRobinRouterClass = classOf[RoundRobinRouter]
  val RandomRouterClass = classOf[RandomRouter]
  val BroadcastRouterClass = classOf[BroadcastRouter]
  val ScatterGatherRouterClass = classOf[ScatterGatherFirstCompletedRouter]
}
