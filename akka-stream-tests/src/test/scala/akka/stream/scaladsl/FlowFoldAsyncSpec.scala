/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.ActorMaterializer
import akka.stream.Supervision.{ restartingDecider, resumingDecider }
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.testkit.TestLatch
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NoStackTrace

class FlowFoldAsyncSpec extends StreamSpec {
  implicit val materializer = ActorMaterializer()
  implicit def ec = materializer.executionContext
  val timeout = Timeout(3.seconds)

  "A FoldAsync" must {
    val input = 1 to 100
    val expected = input.sum
    val inputSource = Source(input)
    val foldSource = inputSource.foldAsync[Int](0) { (a, b) ⇒
      Future(a + b)
    }
    val flowDelayMS = 100L
    val foldFlow = Flow[Int].foldAsync(0) {
      (a, b) ⇒ Future { Thread.sleep(flowDelayMS); a + b }
    }
    val foldSink = Sink.foldAsync[Int, Int](0) { (a, b) ⇒ Future(a + b) }

    "work when using Source.foldAsync" in assertAllStagesStopped {
      foldSource.runWith(Sink.head).futureValue(timeout) should ===(expected)
    }

    "work when using Sink.foldAsync" in assertAllStagesStopped {
      inputSource.runWith(foldSink).futureValue(timeout) should ===(expected)
    }

    "work when using Flow.foldAsync" in assertAllStagesStopped {
      val flowTimeout =
        Timeout((flowDelayMS * input.size).milliseconds + 3.seconds)

      inputSource.via(foldFlow).runWith(Sink.head).
        futureValue(flowTimeout) should ===(expected)
    }

    "work when using Source.foldAsync + Flow.foldAsync + Sink.foldAsync" in assertAllStagesStopped {
      foldSource.via(foldFlow).runWith(foldSink).
        futureValue(timeout) should ===(expected)
    }

    "propagate an error" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = inputSource.map(x ⇒ if (x > 50) throw error else x).runFoldAsync[NotUsed](NotUsed)(noneAsync)
      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "complete future with failure when folding function throws" in assertAllStagesStopped {
      val error = new Exception with NoStackTrace
      val future = inputSource.runFoldAsync(0) { (x, y) ⇒
        if (x > 50) Future.failed(error) else Future(x + y)
      }

      the[Exception] thrownBy Await.result(future, 3.seconds) should be(error)
    }

    "not blow up with high request counts" in {
      val probe = TestSubscriber.manualProbe[Long]()
      var i = 0

      Source.fromIterator(() ⇒ Iterator.fill[Int](10000) { i += 1; i }).
        foldAsync(1L) { (a, b) ⇒ Future(a + b) }.
        runWith(Sink.asPublisher(true)).subscribe(probe)

      val subscription = probe.expectSubscription()
      subscription.request(Int.MaxValue)

      probe.expectNext(50005001L)
      probe.expectComplete()
    }

    "signal future failure" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      implicit val ec = system.dispatcher
      Source(1 to 5).foldAsync(0) { (_, n) ⇒
        Future(if (n == 3) throw TE("err1") else n)
      }.to(Sink.fromSubscriber(probe)).run()

      val sub = probe.expectSubscription()
      sub.request(10)
      probe.expectError().getMessage should be("err1")
    }

    "signal error from foldAsync" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(4)(n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError().getMessage should be("err2")
      latch.countDown()
    }

    "resume after future failure" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[(Int, Int)]()
      implicit val ec = system.dispatcher
      Source(1 to 5).foldAsync(0 → 1) {
        case ((i, res), n) ⇒
          Future {
            if (n == 3) throw new RuntimeException("err3") with NoStackTrace
            else n → (i + (res * n))
          }
      }.withAttributes(supervisionStrategy(resumingDecider)).
        to(Sink.fromSubscriber(probe)).run()

      val sub = probe.expectSubscription()
      sub.request(10)
      probe.expectNext(5 → 74)
      probe.expectComplete()
    }

    "restart after future failure" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[(Int, Int)]()
      implicit val ec = system.dispatcher
      Source(1 to 5).foldAsync(0 → 1) {
        case ((i, res), n) ⇒
          Future {
            if (n == 3) throw new RuntimeException("err3") with NoStackTrace
            else n → (i + (res * n))
          }
      }.withAttributes(supervisionStrategy(restartingDecider)).
        to(Sink.fromSubscriber(probe)).run()

      val sub = probe.expectSubscription()
      sub.request(10)
      probe.expectNext(5 → 24)
      probe.expectComplete()
    }

    "resume after multiple failures" in assertAllStagesStopped {
      val futures: List[Future[String]] = List(
        Future.failed(Utils.TE("failure1")),
        Future.failed(Utils.TE("failure2")),
        Future.failed(Utils.TE("failure3")),
        Future.failed(Utils.TE("failure4")),
        Future.failed(Utils.TE("failure5")),
        Future.successful("happy!"))

      Source(futures).mapAsync(2)(identity).
        withAttributes(supervisionStrategy(resumingDecider)).runWith(Sink.head).
        futureValue(timeout) should ===("happy!")
    }

    "finish after future failure" in assertAllStagesStopped {
      Source(1 to 3).foldAsync(1) { (_, n) ⇒
        Future {
          if (n == 3) throw new RuntimeException("err3b") with NoStackTrace
          else n
        }
      }.withAttributes(supervisionStrategy(resumingDecider))
        .grouped(10).runWith(Sink.head).
        futureValue(Timeout(1.second)) should ===(Seq(2))
    }

    "resume when foldAsync throws" in {
      val c = TestSubscriber.manualProbe[(Int, Int)]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).foldAsync(0 → 1) {
        case ((i, res), n) ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n → (i + (res * n)))
      }.withAttributes(supervisionStrategy(resumingDecider)).
        to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(5 → 74)
      c.expectComplete()
    }

    "restart when foldAsync throws" in {
      val c = TestSubscriber.manualProbe[(Int, Int)]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).foldAsync(0 → 1) {
        case ((i, res), n) ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n → (i + (res * n)))
      }.withAttributes(supervisionStrategy(restartingDecider)).
        to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(5 → 24)
      c.expectComplete()
    }

    "signal NPE when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b")).foldAsync("") { (_, elem) ⇒
        Future.successful(null.asInstanceOf[String])
      }.to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError().getMessage should be(ReactiveStreamsCompliance.ElementMustNotBeNullMsg)
    }

    "resume when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b", "c")).foldAsync("") { (str, elem) ⇒
        if (elem == "b") Future.successful(null.asInstanceOf[String])
        else Future.successful(str + elem)
      }.withAttributes(supervisionStrategy(resumingDecider)).
        to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext("ac") // 1: "" + "a"; 2: null => resume "a"; 3: "a" + "c"
      c.expectComplete()
    }

    "restart when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b", "c")).foldAsync("") { (str, elem) ⇒
        if (elem == "b") Future.successful(null.asInstanceOf[String])
        else Future.successful(str + elem)
      }.withAttributes(supervisionStrategy(restartingDecider)).
        to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext("c") // 1: "" + "a"; 2: null => restart ""; 3: "" + "c"
      c.expectComplete()
    }

    "should handle cancel properly" in assertAllStagesStopped {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(pub).
        foldAsync(0) { (_, n) ⇒ Future.successful(n) }.
        runWith(Sink.fromSubscriber(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()
    }

    "complete future and return zero given an empty stream" in assertAllStagesStopped {
      val futureValue =
        Source.fromIterator[Int](() ⇒ Iterator.empty)
          .runFoldAsync(0)((acc, elem) ⇒ Future.successful(acc + elem))

      Await.result(futureValue, remainingOrDefault) should be(0)
    }

    "complete future and return zero + item given a stream of one item" in assertAllStagesStopped {
      val futureValue =
        Source.single(100)
          .runFoldAsync(5)((acc, elem) ⇒ Future.successful(acc + elem))

      Await.result(futureValue, remainingOrDefault) should be(105)
    }
  }

  // Keep
  def noneAsync[L, R]: (L, R) ⇒ Future[NotUsed] = { (_: Any, _: Any) ⇒
    Future.successful(NotUsed)
  }.asInstanceOf[(L, R) ⇒ Future[NotUsed]]

}
