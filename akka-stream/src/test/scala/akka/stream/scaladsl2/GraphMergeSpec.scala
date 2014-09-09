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
      val source1 = FlowFrom((0 to 3).iterator)
      val source2 = FlowFrom((4 to 9).iterator)
      val source3 = FlowFrom(List.empty[Int].iterator)
      val probe = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val m1 = Merge[Int]("m1")
        val m2 = Merge[Int]("m2")
        val m3 = Merge[Int]("m3")

        source1 ~> m1 ~> FlowFrom[Int].map(_ * 2) ~> m2 ~> FlowFrom[Int].map(_ / 2).map(_ + 1) ~> SubscriberSink(probe)
        source2 ~> m1
        source3 ~> m2

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
      val source1 = FlowFrom(List(1))
      val source2 = FlowFrom(List(2))
      val source3 = FlowFrom(List(3))
      val source4 = FlowFrom(List(4))
      val source5 = FlowFrom(List(5))
      val source6 = FlowFrom(List.empty[Int])

      val probe = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        val merge = Merge[Int]("merge")

        source1 ~> merge ~> FlowFrom[Int] ~> SubscriberSink(probe)
        source2 ~> merge
        source3 ~> merge
        source4 ~> merge
        source5 ~> merge
        source6 ~> merge

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
