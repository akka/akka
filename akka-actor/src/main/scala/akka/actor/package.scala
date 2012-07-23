/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import language.implicitConversions

package object actor {
  implicit def actorRef2Scala(ref: ActorRef): ScalaActorRef = ref.asInstanceOf[ScalaActorRef]
  implicit def scala2ActorRef(ref: ScalaActorRef): ActorRef = ref.asInstanceOf[ActorRef]
}
