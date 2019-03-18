/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util
import java.util.function
import java.util.function.{ BiConsumer, BinaryOperator, Supplier, ToIntFunction }
import java.util.stream.Collector.Characteristics
import java.util.stream.{ Collector, Collectors }

import akka.stream._
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.DefaultTimeout
import org.reactivestreams.Publisher
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class SinkSpec extends StreamSpec with DefaultTimeout with ScalaFutures {

  import GraphDSL.Implicits._

  implicit val materializer = ActorMaterializer()

  "A Sink" must {
    "be composable without importing modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink.fromGraph(GraphDSL.create() { implicit b =>
        val bcast = b.add(Broadcast[Int](3))
        for (i <- 0 to 2) bcast.out(i).filter(_ == i) ~> Sink.fromSubscriber(probes(i))
        SinkShape(bcast.in)
      })
      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())
      subscriptions.foreach { s =>
        s.request(3)
      }
      probes.zipWithIndex.foreach { case (p, i) => p.expectNext(i) }
      probes.foreach { case p                   => p.expectComplete() }
    }

    "be composable with importing 1 module" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink.fromGraph(GraphDSL.create(Sink.fromSubscriber(probes(0))) { implicit b => s0 =>
        val bcast = b.add(Broadcast[Int](3))
        bcast.out(0) ~> Flow[Int].filter(_ == 0) ~> s0.in
        for (i <- 1 to 2) bcast.out(i).filter(_ == i) ~> Sink.fromSubscriber(probes(i))
        SinkShape(bcast.in)
      })
      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())
      subscriptions.foreach { s =>
        s.request(3)
      }
      probes.zipWithIndex.foreach { case (p, i) => p.expectNext(i) }
      probes.foreach { case p                   => p.expectComplete() }
    }

    "be composable with importing 2 modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink =
        Sink.fromGraph(GraphDSL.create(Sink.fromSubscriber(probes(0)), Sink.fromSubscriber(probes(1)))(List(_, _)) {
          implicit b => (s0, s1) =>
            val bcast = b.add(Broadcast[Int](3))
            bcast.out(0).filter(_ == 0) ~> s0.in
            bcast.out(1).filter(_ == 1) ~> s1.in
            bcast.out(2).filter(_ == 2) ~> Sink.fromSubscriber(probes(2))
            SinkShape(bcast.in)
        })
      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())
      subscriptions.foreach { s =>
        s.request(3)
      }
      probes.zipWithIndex.foreach { case (p, i) => p.expectNext(i) }
      probes.foreach { case p                   => p.expectComplete() }
    }

    "be composable with importing 3 modules" in {
      val probes = Array.fill(3)(TestSubscriber.manualProbe[Int])
      val sink = Sink.fromGraph(
        GraphDSL.create(Sink.fromSubscriber(probes(0)), Sink.fromSubscriber(probes(1)), Sink.fromSubscriber(probes(2)))(
          List(_, _, _)) { implicit b => (s0, s1, s2) =>
          val bcast = b.add(Broadcast[Int](3))
          bcast.out(0).filter(_ == 0) ~> s0.in
          bcast.out(1).filter(_ == 1) ~> s1.in
          bcast.out(2).filter(_ == 2) ~> s2.in
          SinkShape(bcast.in)
        })
      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())
      subscriptions.foreach { s =>
        s.request(3)
      }
      probes.zipWithIndex.foreach { case (p, i) => p.expectNext(i) }
      probes.foreach { case p                   => p.expectComplete() }
    }

    "combine to many outputs with simplified API" in {
      val probes = Seq.fill(3)(TestSubscriber.manualProbe[Int]())
      val sink =
        Sink.combine(Sink.fromSubscriber(probes(0)), Sink.fromSubscriber(probes(1)), Sink.fromSubscriber(probes(2)))(
          Broadcast[Int](_))

      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())

      subscriptions.foreach { s =>
        s.request(1)
      }
      probes.foreach { p =>
        p.expectNext(0)
      }

      subscriptions.foreach { s =>
        s.request(2)
      }
      probes.foreach { p =>
        p.expectNextN(List(1, 2))
        p.expectComplete
      }
    }

    "combine to two sinks with simplified API" in {
      val probes = Seq.fill(2)(TestSubscriber.manualProbe[Int]())
      val sink = Sink.combine(Sink.fromSubscriber(probes(0)), Sink.fromSubscriber(probes(1)))(Broadcast[Int](_))

      Source(List(0, 1, 2)).runWith(sink)

      val subscriptions = probes.map(_.expectSubscription())

      subscriptions.foreach { s =>
        s.request(1)
      }
      probes.foreach { p =>
        p.expectNext(0)
      }

      subscriptions.foreach { s =>
        s.request(2)
      }
      probes.foreach { p =>
        p.expectNextN(List(1, 2))
        p.expectComplete
      }
    }

    "suitably override attribute handling methods" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].async.addAttributes(none).named("name")

      s.traversalBuilder.attributes.filtered[Name] shouldEqual List(Name("name"), Name("headSink"))
      s.traversalBuilder.attributes.getFirst[AsyncBoundary.type] shouldEqual (Some(AsyncBoundary))
    }

    "given one attribute of a class should correctly get it as first attribute with default value" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].async.addAttributes(none).named("name")

      s.traversalBuilder.attributes.filtered[Name] shouldEqual List(Name("name"), Name("headSink"))
    }

    "given one attribute of a class should correctly get it as last attribute with default value" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].async.addAttributes(none).named("name")

      s.traversalBuilder.attributes.get[Name](Name("default")) shouldEqual Name("name")
    }

    "given no attributes of a class when getting first attribute with default value should get default value" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].withAttributes(none).async

      s.traversalBuilder.attributes.getFirst[Name](Name("default")) shouldEqual Name("default")
    }

    "given no attributes of a class when getting last attribute with default value should get default value" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].withAttributes(none).async

      s.traversalBuilder.attributes.get[Name](Name("default")) shouldEqual Name("default")
    }

    "given multiple attributes of a class when getting first attribute with default value should get first attribute" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].withAttributes(none).async.named("name").named("another_name")

      s.traversalBuilder.attributes.getFirst[Name](Name("default")) shouldEqual Name("name")
    }

    "given multiple attributes of a class when getting last attribute with default value should get last attribute" in {
      import Attributes._
      val s: Sink[Int, Future[Int]] = Sink.head[Int].async.addAttributes(none).named("name").named("another_name")

      s.traversalBuilder.attributes.get[Name](Name("default")) shouldEqual Name("another_name")
    }

    "support contramap" in {
      Source(0 to 9).toMat(Sink.seq.contramap(_ + 1))(Keep.right).run().futureValue should ===(1 to 10)
    }
  }

  "Java collector Sink" must {

    class TestCollector(
        _supplier: () => Supplier[Array[Int]],
        _accumulator: () => BiConsumer[Array[Int], Int],
        _combiner: () => BinaryOperator[Array[Int]],
        _finisher: () => function.Function[Array[Int], Int])
        extends Collector[Int, Array[Int], Int] {
      override def supplier(): Supplier[Array[Int]] = _supplier()
      override def combiner(): BinaryOperator[Array[Int]] = _combiner()
      override def finisher(): function.Function[Array[Int], Int] = _finisher()
      override def accumulator(): BiConsumer[Array[Int], Int] = _accumulator()
      override def characteristics(): util.Set[Characteristics] = util.Collections.emptySet()
    }

    val intIdentity: ToIntFunction[Int] = new ToIntFunction[Int] {
      override def applyAsInt(value: Int): Int = value
    }

    def supplier(): Supplier[Array[Int]] = new Supplier[Array[Int]] {
      override def get(): Array[Int] = new Array(1)
    }
    def accumulator(): BiConsumer[Array[Int], Int] = new BiConsumer[Array[Int], Int] {
      override def accept(a: Array[Int], b: Int): Unit = a(0) = intIdentity.applyAsInt(b)
    }

    def combiner(): BinaryOperator[Array[Int]] = new BinaryOperator[Array[Int]] {
      override def apply(a: Array[Int], b: Array[Int]): Array[Int] = {
        a(0) += b(0); a
      }
    }
    def finisher(): function.Function[Array[Int], Int] = new function.Function[Array[Int], Int] {
      override def apply(a: Array[Int]): Int = a(0)
    }

    "work in the happy case" in {
      Source(1 to 100)
        .map(_.toString)
        .runWith(StreamConverters.javaCollector(() => Collectors.joining(", ")))
        .futureValue should ===((1 to 100).mkString(", "))
    }

    "work parallelly in the happy case" in {
      Source(1 to 100)
        .runWith(StreamConverters.javaCollectorParallelUnordered(4)(() => Collectors.summingInt[Int](intIdentity)))
        .futureValue
        .toInt should ===(5050)
    }

    "be reusable" in {
      val sink = StreamConverters.javaCollector[Int, Integer](() => Collectors.summingInt[Int](intIdentity))
      Source(1 to 4).runWith(sink).futureValue.toInt should ===(10)

      // Collector has state so it preserves all previous elements that went though
      Source(4 to 6).runWith(sink).futureValue.toInt should ===(15)
    }

    "be reusable with parallel version" in {
      val sink = StreamConverters.javaCollectorParallelUnordered(4)(() => Collectors.summingInt[Int](intIdentity))

      Source(1 to 4).runWith(sink).futureValue.toInt should ===(10)
      Source(4 to 6).runWith(sink).futureValue.toInt should ===(15)
    }

    "fail if getting the supplier fails" in {
      def failedSupplier(): Supplier[Array[Int]] = throw TE("")
      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(failedSupplier _, accumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if the supplier fails" in {
      def failedSupplier(): Supplier[Array[Int]] = new Supplier[Array[Int]] {
        override def get(): Array[Int] = throw TE("")
      }
      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(failedSupplier _, accumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if getting the accumulator fails" in {
      def failedAccumulator(): BiConsumer[Array[Int], Int] = throw TE("")

      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, failedAccumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if the accumulator fails" in {
      def failedAccumulator(): BiConsumer[Array[Int], Int] = new BiConsumer[Array[Int], Int] {
        override def accept(a: Array[Int], b: Int): Unit = throw TE("")
      }

      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, failedAccumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if getting the finisher fails" in {
      def failedFinisher(): function.Function[Array[Int], Int] = throw TE("")

      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, accumulator _, combiner _, failedFinisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if the finisher fails" in {
      def failedFinisher(): function.Function[Array[Int], Int] = new function.Function[Array[Int], Int] {
        override def apply(a: Array[Int]): Int = throw TE("")
      }
      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, accumulator _, combiner _, failedFinisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

  }

  "The ignore sink" should {

    "fail its materialized value on abrupt materializer termination" in {
      val mat = ActorMaterializer()

      val matVal = Source.maybe[Int].runWith(Sink.ignore)(mat)

      mat.shutdown()

      matVal.failed.futureValue shouldBe a[AbruptStageTerminationException]
    }
  }

  "The reduce sink" must {
    "sum up 1 to 10 correctly" in {
      //#reduce-operator-example
      val source = Source(1 to 10)
      val result = source.runWith(Sink.reduce[Int]((a, b) => a + b))
      result.map(println)(system.dispatcher)
      // 55
      //#reduce-operator-example
      assert(result.futureValue == (1 to 10 sum))
    }
  }

  "Sink pre-materialization" must {
    "materialize the sink and wrap its exposed publisher in a Source" in {
      val publisherSink: Sink[String, Publisher[String]] = Sink.asPublisher[String](false)
      val (matPub, sink) = publisherSink.preMaterialize()

      val probe = Source.fromPublisher(matPub).runWith(TestSink.probe)
      probe.expectNoMessage(100.millis)

      Source.single("hello").runWith(sink)

      probe.ensureSubscription()
      probe.requestNext("hello")
      probe.expectComplete()
    }
    "materialize the sink and wrap its exposed publisher(fanout) in a Source twice" in {
      val publisherSink: Sink[String, Publisher[String]] = Sink.asPublisher[String](fanout = true)
      val (matPub, sink) = publisherSink.preMaterialize()

      val probe1 = Source.fromPublisher(matPub).runWith(TestSink.probe)
      val probe2 = Source.fromPublisher(matPub).runWith(TestSink.probe)

      Source.single("hello").runWith(sink)

      probe1.ensureSubscription()
      probe1.requestNext("hello")
      probe1.expectComplete()

      probe2.ensureSubscription()
      probe2.requestNext("hello")
      probe2.expectComplete()
    }
    "materialize the sink and wrap its exposed publisher(not fanout), should fail the second materialization" in {
      val publisherSink: Sink[String, Publisher[String]] = Sink.asPublisher[String](fanout = false)
      val (matPub, sink) = publisherSink.preMaterialize()

      val probe1 = Source.fromPublisher(matPub).runWith(TestSink.probe)
      val probe2 = Source.fromPublisher(matPub).runWith(TestSink.probe)

      Source.single("hello").runWith(sink)

      probe1.ensureSubscription()
      probe1.requestNext("hello")
      probe1.expectComplete()

      probe2.ensureSubscription()
      probe2.expectError().getMessage should include("only supports one subscriber")
    }
  }

}
