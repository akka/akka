/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import language.implicitConversions

package object actor {
  @deprecated("Use akka.actor.ActorRef.actorRef2Scala instead", "2.6.5")
  @inline def actorRef2Scala(ref: ActorRef): ScalaActorRef = ref.asInstanceOf[ScalaActorRef]

  @inline implicit def scala2ActorRef(ref: ScalaActorRef): ActorRef = ref.asInstanceOf[ActorRef]
}
