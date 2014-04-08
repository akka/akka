/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import scala.concurrent.duration.FiniteDuration
import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.Subscription

sealed trait ConsumerEvent
case class OnSubscribe(subscription: Subscription) extends ConsumerEvent
case class OnNext[I](element: I) extends ConsumerEvent
case object OnComplete extends ConsumerEvent
case class OnError(cause: Throwable) extends ConsumerEvent

trait ConsumerProbe[I] extends Consumer[I] {
  def expectSubscription(): Subscription
  def expectEvent(event: ConsumerEvent): Unit
  def expectNext(element: I): Unit
  def expectNext(e1: I, e2: I, es: I*): Unit
  def expectNext(): I
  def expectError(cause: Throwable): Unit
  def expectError(): Throwable
  def expectErrorOrSubscriptionFollowedByError(cause: Throwable): Unit
  def expectErrorOrSubscriptionFollowedByError(): Throwable
  def expectComplete(): Unit

  def expectNoMsg(): Unit
  def expectNoMsg(max: FiniteDuration): Unit
}
