/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.javadsl

import akka.actor.typed._
import akka.NotUsed
import akka.stream.javadsl._
import akka.stream.typed

/**
 * Collection of Sinks aimed at integrating with typed Actors.
 */
object ActorSink {
  /**
   * Sends the elements of the stream to the given `ActorRef`.
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure a the throwable that was signaled
   * to the stream is adapted to the Actors protocol using `onFailureMessage` and
   * then then sent to the destination actor.
   *
   * It will request at most `maxInputBufferSize` number of elements from
   * upstream, but there is no back-pressure signal from the destination actor,
   * i.e. if the actor is not consuming the messages fast enough the mailbox
   * of the actor will grow. For potentially slow consumer actors it is recommended
   * to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
   * limiting operator in front of this `Sink`.
   */
  def actorRef[T](ref: ActorRef[T], onCompleteMessage: T, onFailureMessage: akka.japi.function.Function[Throwable, T]): Sink[T, NotUsed] =
    typed.scaladsl.ActorSink.actorRef(ref, onCompleteMessage, onFailureMessage.apply).asJava

  /**
   * Sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
   * First element is always `onInitMessage`, then stream is waiting for acknowledgement message
   * `ackMessage` from the given actor which means that it is ready to process
   * elements. It also requires `ackMessage` message after each stream element
   * to make backpressure work.
   *
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure - result of `onFailureMessage(throwable)`
   * function will be sent to the destination actor.
   */
  def actorRefWithAck[T, M, A](
    ref:               ActorRef[M],
    messageAdapter:    akka.japi.function.Function2[ActorRef[A], T, M],
    onInitMessage:     akka.japi.function.Function[ActorRef[A], M],
    ackMessage:        A,
    onCompleteMessage: M,
    onFailureMessage:  akka.japi.function.Function[Throwable, M]): Sink[T, NotUsed] =
    typed.scaladsl.ActorSink.actorRefWithAck(
      ref, messageAdapter.apply, onInitMessage.apply, ackMessage, onCompleteMessage, onFailureMessage.apply).asJava

}
