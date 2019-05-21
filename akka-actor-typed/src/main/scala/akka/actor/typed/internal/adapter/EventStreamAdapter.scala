/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.InternalApi

/**
 * INTERNAL API
 * Encapsulates the [[akka.actor.ActorSystem.eventStream]] in a [[Behavior]]
 */
@InternalApi private[akka] object EventStreamAdapter {

  private[akka] val behavior: Behavior[Command] =
    Behaviors.setup { ctx =>
      val eventStream = ctx.system.toUntyped.eventStream
      eventStreamBehavior(eventStream)
    }

  private def eventStreamBehavior(eventStream: akka.event.EventStream): Behavior[Command] =
    Behaviors.receiveMessage {
      case Publish(event) =>
        eventStream.publish(event)
        Behaviors.same
      case s @ Subscribe(subscriber) =>
        eventStream.subscribe(subscriber.toUntyped, s.topic)
        Behaviors.same
      case Unsubscribe(subscriber) =>
        eventStream.unsubscribe(subscriber.toUntyped)
        Behaviors.same
    }

}
