/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit2.TwoStreamsSetup
import akka.stream.scaladsl2.FlowGraphImplicits._

class GraphMergeSpec extends TwoStreamsSetup {

  override type Outputs = Int
  val op = Merge[Int]
  override def operationUnderTestLeft = op
  override def operationUnderTestRight = op

  "merge" must {

    "work in the happy case" in {
      // Different input sizes (4 and 6)
      val tap1 = Source((0 to 3).iterator)
      val tap2 = Source((4 to 9).iterator)
      val tap3 = Source(List.empty[Int].iterator)
      val probe = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val m1 = Merge[Int]("m1")
        val m2 = Merge[Int]("m2")
        val m3 = Merge[Int]("m3")

        tap1 ~> m1 ~> Flow[Int].map(_ * 2) ~> m2 ~> Flow[Int].map(_ / 2).map(_ + 1) ~> SubscriberDrain(probe)
        tap2 ~> m1
        tap3 ~> m2

      }.run()

      val subscription = probe.expectSubscription()

      var collected = Set.empty[Int]
      for (_ ← 1 to 10) {
        subscription.request(1)
        collected += probe.expectNext()
      }

      collected should be(Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      probe.expectComplete()
    }

    "work with n-way merge" in {
      val tap1 = Source(List(1))
      val tap2 = Source(List(2))
      val tap3 = Source(List(3))
      val tap4 = Source(List(4))
      val tap5 = Source(List(5))
      val tap6 = Source(List.empty[Int])

      val probe = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val merge = Merge[Int]("merge")

        tap1 ~> merge ~> Flow[Int] ~> SubscriberDrain(probe)
        tap2 ~> merge
        tap3 ~> merge
        tap4 ~> merge
        tap5 ~> merge
        tap6 ~> merge

      }.run()

      val subscription = probe.expectSubscription()

      var collected = Set.empty[Int]
      for (_ ← 1 to 5) {
        subscription.request(1)
        collected += probe.expectNext()
      }

      collected should be(Set(1, 2, 3, 4, 5))
      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(4)
      subscriber1.expectNext(1)
      subscriber1.expectNext(2)
      subscriber1.expectNext(3)
      subscriber1.expectNext(4)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), completedPublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(4)
      subscriber2.expectNext(1)
      subscriber2.expectNext(2)
      subscriber2.expectNext(3)
      subscriber2.expectNext(4)
      subscriber2.expectComplete()
    }

    "work with one delayed completed and one nonempty publisher" in {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(4)
      subscriber1.expectNext(1)
      subscriber1.expectNext(2)
      subscriber1.expectNext(3)
      subscriber1.expectNext(4)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), soonToCompletePublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(4)
      subscriber2.expectNext(1)
      subscriber2.expectNext(2)
      subscriber2.expectNext(3)
      subscriber2.expectNext(4)
      subscriber2.expectComplete()
    }

    "work with one immediately failed and one nonempty publisher" in {
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

    "work with one delayed failed and one nonempty publisher" in {
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

  }

}
