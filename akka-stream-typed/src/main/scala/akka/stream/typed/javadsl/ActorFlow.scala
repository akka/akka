/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.javadsl

import java.util.function.BiFunction

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream.javadsl.Flow
import akka.util.JavaDurationConverters

import scala.concurrent.duration._

/**
 * Collection of Flows aimed at integrating with typed Actors.
 */
object ActorFlow {

  /**
   * Use the `ask` pattern to send a request-reply message to the target `ref` actor.
   * If any of the asks times out it will fail the stream with a [[java.util.concurrent.TimeoutException]].
   *
   * Do not forget to include the expected response type in the method call, like so:
   *
   * {{{
   * flow.via(ActorFlow.<String, AskMe, String>ask(ref, timeout, (msg, replyTo) -> new AskMe(msg, replyTo)))
   * // or simply
   * flow.via(ActorFlow.ask(ref, timeout, AskMe::new))
   * }}}
   *
   * otherwise `Nothing` will be assumed, which is most likely not what you want.
   *
   * Defaults to parallelism of 2 messages in flight, since while one ask message may be being worked on, the second one
   * still be in the mailbox, so defaulting to sending the second one a bit earlier than when first ask has replied maintains
   * a slightly healthier throughput.
   *
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated,
   * or with an [[java.util.concurrent.TimeoutException]] in case the ask exceeds the timeout passed in.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the futures (in submission order) created by the ask pattern internally are completed
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Fails when''' the passed in actor terminates, or a timeout is exceeded in any of the asks performed
   *
   * '''Cancels when''' downstream cancels
   *
   * @tparam I Incoming element type of the Flow
   * @tparam Q Question message type that is spoken by the target actor
   * @tparam A Answer type that the Actor is expected to reply with, it will become the Output type of this Flow
   */
  def ask[I, Q, A](ref: ActorRef[Q], timeout: java.time.Duration, makeMessage: BiFunction[I, ActorRef[A], Q]): Flow[I, A, NotUsed] =
    akka.stream.typed.scaladsl.ActorFlow.ask[I, Q, A](parallelism = 2)(ref)((i, ref) ⇒ makeMessage(i, ref))(JavaDurationConverters.asFiniteDuration(timeout))
      .asJava

  /**
   * Use the `ask` pattern to send a request-reply message to the target `ref` actor.
   * If any of the asks times out it will fail the stream with a [[java.util.concurrent.TimeoutException]].
   *
   * Do not forget to include the expected response type in the method call, like so:
   *
   * {{{
   * flow.via(ActorFlow.<String, AskMe, String>ask(ref, timeout, (msg, replyTo) -> new AskMe(msg, replyTo)))
   * // or simply
   * flow.via(ActorFlow.ask(ref, timeout, AskMe::new))
   * }}}
   *
   * otherwise `Nothing` will be assumed, which is most likely not what you want.
   *
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated,
   * or with an [[java.util.concurrent.TimeoutException]] in case the ask exceeds the timeout passed in.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the futures (in submission order) created by the ask pattern internally are completed
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Fails when''' the passed in actor terminates, or a timeout is exceeded in any of the asks performed
   *
   * '''Cancels when''' downstream cancels
   *
   * @tparam I Incoming element type of the Flow
   * @tparam Q Question message type that is spoken by the target actor
   * @tparam A Answer type that the Actor is expected to reply with, it will become the Output type of this Flow
   */
  def ask[I, Q, A](parallelism: Int, ref: ActorRef[Q], timeout: java.time.Duration, makeMessage: (I, ActorRef[A]) ⇒ Q): Flow[I, A, NotUsed] =
    akka.stream.typed.scaladsl.ActorFlow.ask[I, Q, A](parallelism)(ref)((i, ref) ⇒ makeMessage(i, ref))(timeout.toMillis.millis)
      .asJava

}
