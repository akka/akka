/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream._

class GraphZipSpec extends TwoStreamsSetup {
  import FlowGraph.Implicits._

  override type Outputs = (Int, Int)

  override def fixture(b: FlowGraph.Builder[_]): Fixture = new Fixture(b) {
    val zip = b.add(Zip[Int, Int]())

    override def left: Inlet[Int] = zip.in0
    override def right: Inlet[Int] = zip.in1
    override def out: Outlet[(Int, Int)] = zip.out
  }

  "Zip" must {

    "work in the happy case" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[(Int, String)]()

      FlowGraph.closed() { implicit b ⇒
        val zip = b.add(Zip[Int, String]())

        Source(1 to 4) ~> zip.in0
        Source(List("A", "B", "C", "D", "E", "F")) ~> zip.in1

        zip.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext((1, "A"))
      probe.expectNext((2, "B"))

      subscription.request(1)
      probe.expectNext((3, "C"))
      subscription.request(1)
      probe.expectNext((4, "D"))

      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), completedPublisher)
      subscriber2.expectSubscriptionAndComplete()
    }

    "work with one delayed completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      subscriber2.expectSubscriptionAndComplete()
    }

    "work with one immediately failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      subscriber2.expectSubscriptionAndError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToFailPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToFailPublisher)
      val subscription2 = subscriber2.expectSubscriptionAndError(TestException)
    }

  }

}
