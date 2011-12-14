/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object routing {
  type Route = PartialFunction[(akka.actor.ActorRef, Any), Iterable[Destination]]
}
