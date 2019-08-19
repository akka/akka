/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.actor.typed

// #blocking-in-actor
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

object BlockingActor {
  val behavior: Behavior[Int] = Behaviors.receiveMessage {
    case i: Int =>
      // DO NOT DO THIS HERE: this is an example of incorrect code,
      // better alternatives are described futher on.

      //block for 5 seconds, representing blocking I/O, etc
      Thread.sleep(5000)
      println(s"Blocking operation finished: ${i}")
      Behaviors.same
  }
}
// #blocking-in-actor
