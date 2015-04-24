/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NoStackTrace
import akka.stream.ActorFlowMaterializer
import akka.stream.stage._
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.TestLatch
import akka.testkit.TestProbe
import akka.stream.ActorOperationAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.impl.ReactiveStreamsCompliance
import scala.util.Try
import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success

object FlowMapAsyncSpec {
  class MapAsyncOne[In, Out](f: In ⇒ Future[Out])(implicit ec: ExecutionContext) extends AsyncStage[In, Out, Try[Out]] {
    private var elemInFlight: Out = _

    override def onPush(elem: In, ctx: AsyncContext[Out, Try[Out]]) = {
      val future = f(elem)
      val cb = ctx.getAsyncCallback()
      future.onComplete(cb.invoke)
      ctx.holdUpstream()
    }

    override def onPull(ctx: AsyncContext[Out, Try[Out]]) =
      if (elemInFlight != null) {
        val e = elemInFlight
        elemInFlight = null.asInstanceOf[Out]
        pushIt(e, ctx)
      } else ctx.holdDownstream()

    override def onAsyncInput(input: Try[Out], ctx: AsyncContext[Out, Try[Out]]) =
      input match {
        case Failure(ex)                           ⇒ ctx.fail(ex)
        case Success(e) if ctx.isHoldingDownstream ⇒ pushIt(e, ctx)
        case Success(e) ⇒
          elemInFlight = e
          ctx.ignore()
      }

    override def onUpstreamFinish(ctx: AsyncContext[Out, Try[Out]]) =
      if (ctx.isHoldingUpstream) ctx.absorbTermination()
      else ctx.finish()

    private def pushIt(elem: Out, ctx: AsyncContext[Out, Try[Out]]) =
      if (ctx.isFinishing) ctx.pushAndFinish(elem)
      else ctx.pushAndPull(elem)
  }
}

class FlowMapAsyncSpec extends AkkaSpec {
  import FlowMapAsyncSpec._

  implicit val materializer = ActorFlowMaterializer()

  "A Flow with mapAsync" must {

    "produce future elements" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 3).mapAsync(4, n ⇒ Future(n)).runWith(Sink(c))
      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMsg(200.millis)
      sub.request(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "produce future elements in order" in {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 50).mapAsync(4, n ⇒ Future {
        Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10))
        n
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(1000)
      for (n ← 1 to 50) c.expectNext(n)
      c.expectComplete()
    }

    "not run more futures than requested parallelism" in {
      val probe = TestProbe()
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 20).mapAsync(8, n ⇒ Future {
        probe.ref ! n
        n
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      // running 8 in parallel
      probe.receiveN(8).toSet should be((1 to 8).toSet)
      probe.expectNoMsg(500.millis)
      sub.request(1)
      probe.expectMsg(9)
      probe.expectNoMsg(500.millis)
      sub.request(2)
      probe.receiveN(2).toSet should be(Set(10, 11))
      probe.expectNoMsg(500.millis)
      sub.request(10)
      probe.receiveN(9).toSet should be((12 to 20).toSet)
      probe.expectNoMsg(200.millis)

      for (n ← 1 to 13) c.expectNext(n)
      c.expectNoMsg(200.millis)
    }

    "signal future failure" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(4, n ⇒ Future {
        if (n == 3) throw new RuntimeException("err1") with NoStackTrace
        else {
          Await.ready(latch, 10.seconds)
          n
        }
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err1")
      latch.countDown()
    }

    "signal error from mapAsync" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(4, n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err2")
      latch.countDown()
    }

    "resume after future failure" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsync(4, n ⇒ Future {
          if (n == 3) throw new RuntimeException("err3") with NoStackTrace
          else n
        })
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (n ← List(1, 2, 4, 5)) c.expectNext(n)
      c.expectComplete()
    }

    "finish after future failure" in assertAllStagesStopped {
      import system.dispatcher
      Await.result(Source(1 to 3).mapAsync(1, n ⇒ Future {
        if (n == 3) throw new RuntimeException("err3b") with NoStackTrace
        else n
      }).withAttributes(supervisionStrategy(resumingDecider))
        .grouped(10)
        .runWith(Sink.head), 1.second) should be(Seq(1, 2))
    }

    "resume when mapAsync throws" in {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsync(4, n ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (n ← List(1, 2, 4, 5)) c.expectNext(n)
      c.expectComplete()
    }

    "signal NPE when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b")).mapAsync(4, elem ⇒ Future.successful(null)).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be(ReactiveStreamsCompliance.ElementMustNotBeNullMsg)
    }

    "resume when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b", "c"))
        .mapAsync(4, elem ⇒ if (elem == "b") Future.successful(null) else Future.successful(elem))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (elem ← List("a", "c")) c.expectNext(elem)
      c.expectComplete()
    }

    "should handle cancel properly" in assertAllStagesStopped {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      Source(pub).mapAsync(4, Future.successful).runWith(Sink(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }

  "A MapAsyncOne" must {
    import system.dispatcher

    "work in the happy case" in {
      val probe = TestProbe()
      val N = 100
      val f = Source(1 to N).transform(() ⇒ new MapAsyncOne(i ⇒ {
        probe.ref ! i
        Future { Thread.sleep(10); probe.ref ! (i + 10); i * 2 }
      })).grouped(N + 10).runWith(Sink.head)
      Await.result(f, 2.seconds) should ===((1 to N).map(_ * 2))
      probe.receiveN(2 * N) should ===((1 to N).flatMap(x ⇒ List(x, x + 10)))
      probe.expectNoMsg(100.millis)
    }

    "work when futures fail" in {
      val probe = TestSubscriber.manualProbe[Int]
      val ex = new Exception("KABOOM")
      Source.single(1)
        .transform(() ⇒ new MapAsyncOne(_ ⇒ Future.failed(ex)))
        .runWith(Sink(probe))
      val sub = probe.expectSubscription()
      sub.request(1)
      probe.expectError(ex)
    }

    "work when futures fail later" in {
      val probe = TestSubscriber.manualProbe[Int]
      val ex = new Exception("KABOOM")
      Source(List(1, 2))
        .transform(() ⇒ new MapAsyncOne(x ⇒ if (x == 1) Future.successful(1) else Future.failed(ex)))
        .runWith(Sink(probe))
      val sub = probe.expectSubscription()
      sub.request(1)
      probe.expectNext(1)
      probe.expectError(ex)
    }

  }

}
