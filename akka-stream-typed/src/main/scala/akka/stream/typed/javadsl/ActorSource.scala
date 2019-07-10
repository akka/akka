/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.javadsl

import java.util.function.Predicate

import akka.actor.typed._
import akka.japi.JavaPartialFunction
import akka.stream.javadsl._
import akka.stream.{ CompletionStrategy, OverflowStrategy }

/**
 * Collection of Sources aimed at integrating with typed Actors.
 */
object ActorSource {

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.typed.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] is not supported, and an
   * IllegalArgument("Backpressure overflowStrategy not supported") will be thrown if it is passed as argument.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped if there is no demand
   * from downstream. When `bufferSize` is 0 the `overflowStrategy` does not matter. An async boundary is added after
   * this Source; as such, it is never safe to assume the downstream will always generate demand.
   *
   * The stream can be completed successfully by sending the actor reference a [[akka.actor.Status.Success]]
   * (whose content will be ignored) in which case already buffered elements will be signaled before signaling
   * completion, or by sending [[akka.actor.PoisonPill]] in which case completion will be signaled immediately.
   *
   * The stream can be completed with failure by sending a [[akka.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * a [[akka.actor.Status.Success]]) before signaling completion and it receives a [[akka.actor.Status.Failure]],
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * See also [[akka.stream.javadsl.Source.queue]].
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](
      completionMatcher: Predicate[T],
      failureMatcher: akka.japi.function.Function[T, java.util.Optional[Throwable]],
      bufferSize: Int,
      overflowStrategy: OverflowStrategy): Source[T, ActorRef[T]] = {
    akka.stream.typed.scaladsl.ActorSource
      .actorRef(
        { case m if completionMatcher.test(m) => }: PartialFunction[T, Unit],
        new JavaPartialFunction[T, Throwable] {
          override def apply(x: T, isCheck: Boolean): Throwable = {
            val result = failureMatcher(x)
            if (!result.isPresent) throw JavaPartialFunction.noMatch()
            else result.get()
          }
        },
        bufferSize,
        overflowStrategy)
      .asJava
  }

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * and a new message will only be accepted after the previous messages has been consumed and acknowledged back.
   * The stream will complete with failure if a message is sent before the acknowledgement has been replied back.
   *
   * The stream can be completed with failure by sending a message that is matched by `failureMatcher`. The extracted
   * [[Throwable]] will be used to fail the stream. In case the Actor is still draining its internal buffer (after having received
   * a message matched by `completionMatcher`) before signaling completion and it receives a message matched by `failureMatcher`,
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   */
  def actorRefWithAck[T, Ack](
      ackTo: ActorRef[Ack],
      ackMessage: Ack,
      completionMatcher: akka.japi.function.Function[T, java.util.Optional[CompletionStrategy]],
      failureMatcher: akka.japi.function.Function[T, java.util.Optional[Throwable]]): Source[T, ActorRef[T]] =
    akka.stream.typed.scaladsl.ActorSource
      .actorRefWithAck[T, Ack](
        ackTo,
        ackMessage,
        new JavaPartialFunction[T, CompletionStrategy] {
          override def apply(x: T, isCheck: Boolean): CompletionStrategy = {
            val result = completionMatcher(x)
            if (!result.isPresent) throw JavaPartialFunction.noMatch()
            else result.get()
          }
        },
        new JavaPartialFunction[T, Throwable] {
          override def apply(x: T, isCheck: Boolean): Throwable = {
            val result = failureMatcher(x)
            if (!result.isPresent) throw JavaPartialFunction.noMatch()
            else result.get()
          }
        })
      .asJava
}
