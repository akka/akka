package akka.stream.scaladsl

import akka.stream.{ ClosedShape, Inlet, Outlet }
import akka.stream.testkit.{ TestSubscriber, TwoStreamsSetup }

import scala.collection.immutable

class GraphMergePrioritizedSpec extends TwoStreamsSetup {
  import GraphDSL.Implicits._

  override type Outputs = Int

  override def fixture(b: GraphDSL.Builder[_]): Fixture = new Fixture(b) {
    val mergePrioritized = b add MergePrioritized[Outputs](2, Seq(20, 80))

    override def left: Inlet[Outputs] = mergePrioritized.in(0)
    override def right: Inlet[Outputs] = mergePrioritized.in(1)
    override def out: Outlet[Outputs] = mergePrioritized.out

  }

  "merge prioritized" must {
    commonTests()

    "stream data from all sources" in {
      val source1 = Source.fromIterator(() ⇒ (1 to 3).iterator)
      val source2 = Source.fromIterator(() ⇒ (4 to 6).iterator)
      val source3 = Source.fromIterator(() ⇒ (7 to 9).iterator)

      val probe = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(source1, source2, source3)((_, _, _)) { implicit b ⇒ (s1, s2, s3) ⇒
        val merge = b.add(MergePrioritized[Int](3, immutable.Seq(6, 3, 1)))
        s1.out ~> merge.in(0)
        s2.out ~> merge.in(1)
        s3.out ~> merge.in(2)
        merge.out ~> Sink.fromSubscriber(probe)
        ClosedShape
      }).run()

      val subscription = probe.expectSubscription()

      var collected = Seq.empty[Int]
      for (_ ← 1 to 9) {
        subscription.request(1)
        collected :+= probe.expectNext()
      }

      collected.toSet should be(Set(1, 2, 3, 4, 5, 6, 7, 8, 9))
      probe.expectComplete()
    }

    "stream data with priority" in {
      val elementCount = 10000
      val source1 = Source.fromIterator(() ⇒ Seq.fill(elementCount)(1).iterator)
      val source2 = Source.fromIterator(() ⇒ Seq.fill(elementCount)(2).iterator)
      val source3 = Source.fromIterator(() ⇒ Seq.fill(elementCount)(3).iterator)

      val probe = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(source1, source2, source3)((_, _, _)) { implicit b ⇒ (s1, s2, s3) ⇒
        val merge = b.add(MergePrioritized[Int](3, immutable.Seq(6, 3, 1)))
        s1.out ~> merge.in(0)
        s2.out ~> merge.in(1)
        s3.out ~> merge.in(2)
        merge.out ~> Sink.fromSubscriber(probe)
        ClosedShape
      }).run()

      val subscription = probe.expectSubscription()

      var collected = Seq.empty[Int]
      for (_ ← 1 to elementCount) {
        subscription.request(1)
        collected :+= probe.expectNext()
      }

      val ones = collected.count(_ == 1).toDouble
      val twos = collected.count(_ == 2).toDouble
      val threes = collected.count(_ == 3).toDouble

      (ones / twos).round shouldEqual 2
      (ones / threes).round shouldEqual 6
      (twos / threes).round shouldEqual 3
    }

    "stream data when only one source produces" in {
      val elementCount = 10
      val source1 = Source.fromIterator(() ⇒ Seq.fill(elementCount)(1).iterator)
      val source2 = Source.fromIterator(() ⇒ Seq.empty[Int].iterator)
      val source3 = Source.fromIterator(() ⇒ Seq.empty[Int].iterator)

      val probe = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(source1, source2, source3)((_, _, _)) { implicit b ⇒ (s1, s2, s3) ⇒
        val merge = b.add(MergePrioritized[Int](3, immutable.Seq(6, 3, 1)))
        s1.out ~> merge.in(0)
        s2.out ~> merge.in(1)
        s3.out ~> merge.in(2)
        merge.out ~> Sink.fromSubscriber(probe)
        ClosedShape
      }).run()

      val subscription = probe.expectSubscription()

      var collected = Seq.empty[Int]
      for (_ ← 1 to elementCount) {
        subscription.request(1)
        collected :+= probe.expectNext()
      }

      val ones = collected.count(_ == 1)
      val twos = collected.count(_ == 2)
      val threes = collected.count(_ == 3)

      ones shouldEqual elementCount
      twos shouldEqual 0
      threes shouldEqual 0
    }

    "stream data with priority when only two sources produce" in {
      val elementCount = 10000
      val source1 = Source.fromIterator(() ⇒ Seq.fill(elementCount)(1).iterator)
      val source2 = Source.fromIterator(() ⇒ Seq.fill(elementCount)(2).iterator)
      val source3 = Source.fromIterator(() ⇒ Seq.empty[Int].iterator)

      val probe = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(source1, source2, source3)((_, _, _)) { implicit b ⇒ (s1, s2, s3) ⇒
        val merge = b.add(MergePrioritized[Int](3, immutable.Seq(6, 3, 1)))
        s1.out ~> merge.in(0)
        s2.out ~> merge.in(1)
        s3.out ~> merge.in(2)
        merge.out ~> Sink.fromSubscriber(probe)
        ClosedShape
      }).run()

      val subscription = probe.expectSubscription()

      var collected = Seq.empty[Int]
      for (_ ← 1 to elementCount) {
        subscription.request(1)
        collected :+= probe.expectNext()
      }

      val ones = collected.count(_ == 1).toDouble
      val twos = collected.count(_ == 2).toDouble
      val threes = collected.count(_ == 3)

      threes shouldEqual 0
      (ones / twos).round shouldBe 2
    }

    "complete when one of the sources complete if eager complete is set to true" in {
      pending
    }
  }
}
