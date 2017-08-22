/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.actor.ActorSubscriber
import akka.actor.ActorRef
import akka.stream.actor.ActorSubscriberMessage
import akka.actor.Status
import akka.stream.actor.WatermarkRequestStrategy
import akka.actor.Props
import akka.actor.Terminated
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ActorRefSinkActor {
  def props(ref: ActorRef, highWatermark: Int, onCompleteMessage: Any): Props =
    Props(new ActorRefSinkActor(ref, highWatermark, onCompleteMessage))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ActorRefSinkActor(ref: ActorRef, highWatermark: Int, onCompleteMessage: Any) extends ActorSubscriber {
  import ActorSubscriberMessage._

  override val requestStrategy = WatermarkRequestStrategy(highWatermark)

  context.watch(ref)

  def receive = {
    case OnNext(elem) ⇒
      ref.tell(elem, ActorRef.noSender)
    case OnError(cause) ⇒
      ref.tell(Status.Failure(cause), ActorRef.noSender)
      context.stop(self)
    case OnComplete ⇒
      ref.tell(onCompleteMessage, ActorRef.noSender)
      context.stop(self)
    case Terminated(`ref`) ⇒
      context.stop(self) // will cancel upstream
  }

}
