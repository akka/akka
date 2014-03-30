/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import org.reactivestreams.spi.{ Subscriber, Subscription }
import org.reactivestreams.api.Producer
import scala.concurrent.duration.FiniteDuration
import org.reactivestreams.api.Consumer

sealed trait ProducerEvent
case class Subscribe(subscription: Subscription) extends ProducerEvent
case class CancelSubscription(subscription: Subscription) extends ProducerEvent
case class RequestMore(subscription: Subscription, elements: Int) extends ProducerEvent

abstract case class ActiveSubscription[I](subscriber: Subscriber[I]) extends Subscription {
  def sendNext(element: I): Unit
  def sendComplete(): Unit
  def sendError(cause: Exception): Unit

  def expectCancellation(): Unit
  def expectRequestMore(n: Int): Unit
  def expectRequestMore(): Int
}

trait ProducerProbe[I] extends Producer[I] {
  def expectSubscription(): ActiveSubscription[I]
  def expectRequestMore(subscription: Subscription, n: Int): Unit

  def expectNoMsg(): Unit
  def expectNoMsg(max: FiniteDuration): Unit

  def produceTo(consumer: Consumer[I]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)
}
