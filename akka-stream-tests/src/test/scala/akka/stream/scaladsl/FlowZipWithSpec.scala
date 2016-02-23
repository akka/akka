/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.{ BaseTwoStreamsSetup, TestSubscriber }
import org.reactivestreams.Publisher
import scala.concurrent.duration._
import akka.testkit.EventFilter

class FlowZipWithSpec extends BaseTwoStreamsSetup {

  override type Outputs = Int

  override def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    Source.fromPublisher(p1).zipWith(Source.fromPublisher(p2))(_ + _).runWith(Sink.fromSubscriber(subscriber))
    subscriber
  }

  "A ZipWith for Flow " must {

    "work in the happy case" in {
      val probe = TestSubscriber.manualProbe[Outputs]()
      Source(1 to 4).zipWith(Source(10 to 40 by 10))((_: Int) + (_: Int)).runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext(11)
      probe.expectNext(22)

      subscription.request(1)
      probe.expectNext(33)
      subscription.request(1)
      probe.expectNext(44)

      probe.expectComplete()
    }

    "work in the sad case" in {
      val probe = TestSubscriber.manualProbe[Outputs]()
      Source(1 to 4).zipWith(Source(-2 to 2))((_: Int) / (_: Int)).runWith(Sink.fromSubscriber(probe))
      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext(1 / -2)
      probe.expectNext(2 / -1)

      EventFilter[ArithmeticException](occurrences = 1).intercept {
        subscription.request(2)
      }
      probe.expectError() match {
        case a: java.lang.ArithmeticException ⇒ a.getMessage should be("/ by zero")
      }
      probe.expectNoMsg(200.millis)
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), completedPublisher)
      subscriber2.expectSubscriptionAndComplete()
    }

    "work with one delayed completed and one nonempty publisher" in {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      subscriber2.expectSubscriptionAndComplete()
    }

    "work with one immediately failed and one nonempty publisher" in {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      subscriber2.expectSubscriptionAndError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in {
      val subscriber1 = setup(soonToFailPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToFailPublisher)
      subscriber2.expectSubscriptionAndError(TestException)
    }

  }

}
