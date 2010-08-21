/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka

import actor.{ScalaActorRef, ActorRef}

package object actor {
  implicit def actorRef2Scala(ref: ActorRef): ScalaActorRef =
    ref.asInstanceOf[ScalaActorRef]

  implicit def scala2ActorRef(ref: ScalaActorRef): ActorRef =
    ref.asInstanceOf[ActorRef]
}
