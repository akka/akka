/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.util.control.NoStackTrace
import org.reactivestreams.api.{ Consumer, Producer }
import org.reactivestreams.spi.{ Subscriber, Publisher, Subscription }
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.scaladsl.Flow
import akka.stream.testkit.OnSubscribe
import akka.stream.testkit.OnError

abstract class TwoStreamsSetup extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2))

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  val TestException = TE("test")

  type Outputs

  def operationUnderTest(in1: Flow[Int], in2: Producer[Int]): Flow[Outputs]

  def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val consumer = StreamTestKit.consumerProbe[Outputs]
    operationUnderTest(Flow(producerFromPublisher(p1)), producerFromPublisher(p2)).toProducer(materializer).produceTo(consumer)
    consumer
  }

  def producerFromPublisher[T](publisher: Publisher[T]): Producer[T] = new Producer[T] {
    private val pub = publisher
    override def produceTo(consumer: Consumer[T]): Unit = pub.subscribe(consumer.getSubscriber)
    override def getPublisher: Publisher[T] = pub
  }

  def failedPublisher[T]: Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[T]): Unit = {
      subscriber.onError(TestException)
    }
  }

  def completedPublisher[T]: Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[T]): Unit = {
      subscriber.onComplete()
    }
  }

  def nonemptyPublisher[T](elems: Iterator[T]): Publisher[T] = Flow(elems).toProducer(materializer).getPublisher

  def soonToFailPublisher[T]: Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[T]): Unit = subscriber.onSubscribe(FailedSubscription(subscriber))
  }

  def soonToCompletePublisher[T]: Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[T]): Unit = subscriber.onSubscribe(CompletedSubscription(subscriber))
  }

  case class FailedSubscription(subscriber: Subscriber[_]) extends Subscription {
    override def requestMore(elements: Int): Unit = subscriber.onError(TestException)
    override def cancel(): Unit = ()
  }

  case class CompletedSubscription(subscriber: Subscriber[_]) extends Subscription {
    override def requestMore(elements: Int): Unit = subscriber.onComplete()
    override def cancel(): Unit = ()
  }

  def commonTests() = {
    "work with two immediately completed producers" in {
      val consumer = setup(completedPublisher, completedPublisher)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectComplete()
    }

    "work with two delayed completed producers" in {
      val consumer = setup(soonToCompletePublisher, soonToCompletePublisher)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectComplete()
    }

    "work with one immediately completed and one delayed completed producer" in {
      val consumer = setup(completedPublisher, soonToCompletePublisher)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectComplete()
    }

    "work with two immediately failed producers" in {
      val consumer = setup(failedPublisher, failedPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with two delayed failed producers" in {
      val consumer = setup(soonToFailPublisher, soonToFailPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    // Warning: The two test cases below are somewhat implementation specific and might fail if the implementation
    // is changed. They are here to be an early warning though.
    "work with one immediately failed and one delayed failed producer (case 1)" in {
      val consumer = setup(soonToFailPublisher, failedPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one immediately failed and one delayed failed producer (case 2)" in {
      val consumer = setup(failedPublisher, soonToFailPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }
  }

}
