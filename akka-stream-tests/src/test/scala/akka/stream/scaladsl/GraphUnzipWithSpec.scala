/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream._
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import org.reactivestreams.Publisher

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class GraphUnzipWithSpec extends AkkaSpec {

  import GraphDSL.Implicits._

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  val TestException = new RuntimeException("test") with NoStackTrace

  type LeftOutput = Int
  type RightOutput = String

  abstract class Fixture(b: GraphDSL.Builder[_]) {
    def in: Inlet[Int]
    def left: Outlet[LeftOutput]
    def right: Outlet[RightOutput]
  }

  val f: (Int ⇒ (Int, String)) = b ⇒ (b + b, b + "+" + b)

  def fixture(b: GraphDSL.Builder[_]): Fixture = new Fixture(b) {
    val unzip = b.add(UnzipWith[Int, Int, String](f))

    override def in: Inlet[Int] = unzip.in

    override def left: Outlet[Int] = unzip.out0

    override def right: Outlet[String] = unzip.out1
  }

  def setup(p: Publisher[Int]) = {
    val leftSubscriber = TestSubscriber.probe[LeftOutput]()
    val rightSubscriber = TestSubscriber.probe[RightOutput]()

    RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
      val f = fixture(b)

      Source(p) ~> f.in
      f.left ~> Sink(leftSubscriber)
      f.right ~> Sink(rightSubscriber)

      ClosedShape
    }).run()

    (leftSubscriber, rightSubscriber)
  }

  def validateSubscriptionAndComplete(subscribers: (Probe[LeftOutput], Probe[RightOutput])): Unit = {
    subscribers._1.expectSubscriptionAndComplete()
    subscribers._2.expectSubscriptionAndComplete()
  }

  def validateSubscriptionAndError(subscribers: (Probe[LeftOutput], Probe[RightOutput])): Unit = {
    subscribers._1.expectSubscriptionAndError(TestException)
    subscribers._2.expectSubscriptionAndError(TestException)
  }

  "UnzipWith" must {

    "work with immediately completed publisher" in assertAllStagesStopped {
      val subscribers = setup(TestPublisher.empty[Int])
      validateSubscriptionAndComplete(subscribers)
    }

    "work with delayed completed publisher" in assertAllStagesStopped {
      val subscribers = setup(TestPublisher.lazyEmpty)
      validateSubscriptionAndComplete(subscribers)
    }

    "work with two immediately failed publishers" in assertAllStagesStopped {
      val subscribers = setup(TestPublisher.error(TestException))
      validateSubscriptionAndError(subscribers)
    }

    "work with two delayed failed publishers" in assertAllStagesStopped {
      val subscribers = setup(TestPublisher.lazyError(TestException))
      validateSubscriptionAndError(subscribers)
    }

    "work in the happy case" in {
      val leftProbe = TestSubscriber.manualProbe[LeftOutput]()
      val rightProbe = TestSubscriber.manualProbe[RightOutput]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val unzip = b.add(UnzipWith(f))
        Source(1 to 4) ~> unzip.in

        unzip.out0 ~> Flow[LeftOutput].buffer(4, OverflowStrategy.backpressure) ~> Sink(leftProbe)
        unzip.out1 ~> Flow[RightOutput].buffer(4, OverflowStrategy.backpressure) ~> Sink(rightProbe)

        ClosedShape
      }).run()

      val leftSubscription = leftProbe.expectSubscription()
      val rightSubscription = rightProbe.expectSubscription()

      leftSubscription.request(2)
      rightSubscription.request(1)

      leftProbe.expectNext(2)
      leftProbe.expectNext(4)
      leftProbe.expectNoMsg(100.millis)

      rightProbe.expectNext("1+1")
      rightProbe.expectNoMsg(100.millis)

      leftSubscription.request(1)
      rightSubscription.request(2)

      leftProbe.expectNext(6)
      leftProbe.expectNoMsg(100.millis)

      rightProbe.expectNext("2+2")
      rightProbe.expectNext("3+3")
      rightProbe.expectNoMsg(100.millis)

      leftSubscription.request(1)
      rightSubscription.request(1)

      leftProbe.expectNext(8)
      rightProbe.expectNext("4+4")

      leftProbe.expectComplete()
      rightProbe.expectComplete()
    }

    "work in the sad case" in {
      val settings = ActorMaterializerSettings(system)
        .withInputBuffer(initialSize = 1, maxSize = 1)

      val leftProbe = TestSubscriber.manualProbe[LeftOutput]()
      val rightProbe = TestSubscriber.manualProbe[RightOutput]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val unzip = b.add(UnzipWith[Int, Int, String]((b: Int) ⇒ (1 / b, 1 + "/" + b)))

        Source(-2 to 2) ~> unzip.in

        unzip.out0 ~> Sink(leftProbe)
        unzip.out1 ~> Sink(rightProbe)

        ClosedShape
      }).run()

      val leftSubscription = leftProbe.expectSubscription()
      val rightSubscription = rightProbe.expectSubscription()

      def requestFromBoth(): Unit = {
        leftSubscription.request(1)
        rightSubscription.request(1)
      }

      requestFromBoth()
      leftProbe.expectNext(1 / -2)
      rightProbe.expectNext("1/-2")

      requestFromBoth()
      leftProbe.expectNext(1 / -1)
      rightProbe.expectNext("1/-1")

      requestFromBoth()

      leftProbe.expectError() match {
        case a: java.lang.ArithmeticException ⇒ a.getMessage should be("/ by zero")
      }
      rightProbe.expectError()

      leftProbe.expectNoMsg(100.millis)
      rightProbe.expectNoMsg(100.millis)
    }

    "unzipWith expanded Person.unapply (3 ouputs)" in {
      val probe0 = TestSubscriber.manualProbe[String]()
      val probe1 = TestSubscriber.manualProbe[String]()
      val probe2 = TestSubscriber.manualProbe[Int]()

      case class Person(name: String, surname: String, int: Int)

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val unzip = b.add(UnzipWith((a: Person) ⇒ Person.unapply(a).get))

        Source.single(Person("Caplin", "Capybara", 3)) ~> unzip.in

        unzip.out0 ~> Sink(probe0)
        unzip.out1 ~> Sink(probe1)
        unzip.out2 ~> Sink(probe2)

        ClosedShape
      }).run()

      val subscription0 = probe0.expectSubscription()
      val subscription1 = probe1.expectSubscription()
      val subscription2 = probe2.expectSubscription()

      subscription0.request(1)
      subscription1.request(1)
      subscription2.request(1)

      probe0.expectNext("Caplin")
      probe1.expectNext("Capybara")
      probe2.expectNext(3)

      probe0.expectComplete()
      probe1.expectComplete()
      probe2.expectComplete()
    }

    "work with up to 20 outputs" in {
      val probe0 = TestSubscriber.manualProbe[Int]()
      val probe5 = TestSubscriber.manualProbe[String]()
      val probe10 = TestSubscriber.manualProbe[Int]()
      val probe15 = TestSubscriber.manualProbe[String]()
      val probe19 = TestSubscriber.manualProbe[String]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒

        val split20 = (a: (List[Int])) ⇒
          (a(0), a(0).toString,
            a(1), a(1).toString,
            a(2), a(2).toString,
            a(3), a(3).toString,
            a(4), a(4).toString,
            a(5), a(5).toString,
            a(6), a(6).toString,
            a(7), a(7).toString,
            a(8), a(8).toString,
            a(9), a(9).toString)

        // odd input ports will be Int, even input ports will be String
        val unzip = b.add(UnzipWith(split20))

        Source.single((0 to 19).toList) ~> unzip.in

        def createSink[T](o: Outlet[T]) =
          o ~> Flow[T].buffer(1, OverflowStrategy.backpressure) ~> Sink(TestSubscriber.manualProbe[T]())

        unzip.out0 ~> Sink(probe0)
        createSink(unzip.out1)
        createSink(unzip.out2)
        createSink(unzip.out3)
        createSink(unzip.out4)

        unzip.out5 ~> Sink(probe5)
        createSink(unzip.out6)
        createSink(unzip.out7)
        createSink(unzip.out8)
        createSink(unzip.out9)

        unzip.out10 ~> Sink(probe10)
        createSink(unzip.out11)
        createSink(unzip.out12)
        createSink(unzip.out13)
        createSink(unzip.out14)

        unzip.out15 ~> Sink(probe15)
        createSink(unzip.out16)
        createSink(unzip.out17)
        createSink(unzip.out18)

        unzip.out19 ~> Sink(probe19)

        ClosedShape
      }).run()

      probe0.expectSubscription().request(1)
      probe5.expectSubscription().request(1)
      probe10.expectSubscription().request(1)
      probe15.expectSubscription().request(1)
      probe19.expectSubscription().request(1)

      probe0.expectNext(0)
      probe5.expectNext("2")
      probe10.expectNext(5)
      probe15.expectNext("7")
      probe19.expectNext("9")

      probe0.expectComplete()
      probe5.expectComplete()
      probe10.expectComplete()
      probe15.expectComplete()
      probe19.expectComplete()
    }

  }

}
