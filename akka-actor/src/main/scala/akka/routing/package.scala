/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.ActorRef

package object routing {

  case class Destination(sender: ActorRef, recipient: ActorRef)

  type Route = (ActorRef, Any) ⇒ Iterable[Destination]

}