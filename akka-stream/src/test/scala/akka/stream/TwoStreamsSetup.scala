/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import org.reactivestreams.Publisher

import scala.util.control.NoStackTrace

abstract class TwoStreamsSetup extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  val TestException = TE("test")

  type Outputs

  def operationUnderTest(in1: Flow[Int], in2: Publisher[Int]): Flow[Outputs]

  def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = StreamTestKit.SubscriberProbe[Outputs]()
    operationUnderTest(Flow(p1), p2).toPublisher(materializer).subscribe(subscriber)
    subscriber
  }

  def failedPublisher[T]: Publisher[T] = StreamTestKit.errorPublisher[T](TestException)

  def completedPublisher[T]: Publisher[T] = StreamTestKit.emptyPublisher[T]

  def nonemptyPublisher[T](elems: Iterator[T]): Publisher[T] = Flow(elems).toPublisher(materializer)

  def soonToFailPublisher[T]: Publisher[T] = StreamTestKit.lazyErrorPublisher[T](TestException)

  def soonToCompletePublisher[T]: Publisher[T] = StreamTestKit.lazyEmptyPublisher[T]

  def commonTests() = {
    "work with two immediately completed publishers" in {
      val subscriber = setup(completedPublisher, completedPublisher)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with two delayed completed publishers" in {
      val subscriber = setup(soonToCompletePublisher, soonToCompletePublisher)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one immediately completed and one delayed completed publisher" in {
      val subscriber = setup(completedPublisher, soonToCompletePublisher)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with two immediately failed publishers" in {
      val subscriber = setup(failedPublisher, failedPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with two delayed failed publishers" in {
      val subscriber = setup(soonToFailPublisher, soonToFailPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    // Warning: The two test cases below are somewhat implementation specific and might fail if the implementation
    // is changed. They are here to be an early warning though.
    "work with one immediately failed and one delayed failed publisher (case 1)" in {
      val subscriber = setup(soonToFailPublisher, failedPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one immediately failed and one delayed failed publisher (case 2)" in {
      val subscriber = setup(failedPublisher, soonToFailPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }
  }

}
