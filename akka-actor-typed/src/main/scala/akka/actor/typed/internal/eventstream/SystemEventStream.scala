/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.eventstream

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._

private[akka] object SystemEventStream {

  private[akka] val behavior: Behavior[EventStream.Command] =
    Behaviors.setup { ctx =>
      val eventStream = ctx.system.toUntyped.eventStream
      eventStreamBehavior(eventStream)
    }

  private def eventStreamBehavior(eventStream: akka.event.EventStream): Behavior[EventStream.Command] =
    Behaviors.receiveMessage {
      case EventStream.Publish(event) =>
        eventStream.publish(event)
        Behaviors.same
      case s @ EventStream.Subscribe(subscriber) =>
        eventStream.subscribe(subscriber.toUntyped, s.topic)
        Behaviors.same
      case EventStream.Unsubscribe(subscriber) =>
        eventStream.unsubscribe(subscriber.toUntyped)
        Behaviors.same
    }

}
