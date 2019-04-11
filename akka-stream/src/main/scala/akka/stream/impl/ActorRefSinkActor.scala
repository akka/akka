/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.actor.ActorSubscriber
import akka.actor.ActorRef
import akka.stream.actor.ActorSubscriberMessage
import akka.stream.actor.WatermarkRequestStrategy
import akka.actor.Props
import akka.actor.Terminated
import akka.annotation.InternalApi
import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ActorRefSinkActor {
  def props(ref: ActorRef, highWatermark: Int, onCompleteMessage: Any, onFailureMessage: Throwable => Any): Props =
    Props(new ActorRefSinkActor(ref, highWatermark, onCompleteMessage, onFailureMessage))
}

/**
 * INTERNAL API
 */
@silent
@InternalApi private[akka] class ActorRefSinkActor(
    ref: ActorRef,
    highWatermark: Int,
    onCompleteMessage: Any,
    onFailureMessage: Throwable => Any)
    extends ActorSubscriber {
  import ActorSubscriberMessage._

  override val requestStrategy = WatermarkRequestStrategy(highWatermark)

  context.watch(ref)

  def receive = {
    case OnNext(elem) =>
      ref.tell(elem, ActorRef.noSender)
    case OnError(cause) =>
      ref.tell(onFailureMessage(cause), ActorRef.noSender)
      context.stop(self)
    case OnComplete =>
      ref.tell(onCompleteMessage, ActorRef.noSender)
      context.stop(self)
    case Terminated(`ref`) =>
      context.stop(self) // will cancel upstream
  }

}
