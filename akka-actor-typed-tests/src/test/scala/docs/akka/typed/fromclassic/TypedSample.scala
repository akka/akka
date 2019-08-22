/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed.fromclassic

// #hello-world-actor
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

// #hello-world-actor

object TypedSample {

  //#hello-world-actor
  object HelloWorld {
    final case class Greet(whom: String, replyTo: ActorRef[Greeted])
    final case class Greeted(whom: String, from: ActorRef[Greet])

    def apply(): Behavior[HelloWorld.Greet] =
      Behaviors.setup(context => new HelloWorld(context))
  }

  class HelloWorld(context: ActorContext[HelloWorld.Greet]) extends AbstractBehavior[HelloWorld.Greet] {
    import HelloWorld._

    override def onMessage(message: Greet): Behavior[Greet] = {
      context.log.info("Hello {}!", message.whom)
      message.replyTo ! Greeted(message.whom, context.self)
      this
    }
  }
  //#hello-world-actor

}
