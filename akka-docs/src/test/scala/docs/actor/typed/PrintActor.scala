/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.actor.typed

// #print-actor
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

object PrintActor {
  val behavior: Behavior[Integer] =
    Behaviors.receiveMessage(i => {
      println(s"PrintActor: ${i}")
      Behaviors.same
    })
}
// #print-actor
