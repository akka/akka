/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.Supervision._
import akka.stream.impl.ReactiveStreamsCompliance.SpecViolation
import akka.stream.testkit.AkkaSpec
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.impl.fusing.ActorInterpreter
import akka.stream.stage.Stage
import akka.stream.stage.PushPullStage
import akka.stream.stage.Context
import akka.testkit.TestLatch
import org.reactivestreams.{ Subscription, Subscriber, Publisher }
import scala.concurrent.Await
import scala.concurrent.duration._

class ActorInterpreterSpec extends AkkaSpec {
  import FlowGraph.Implicits._

  implicit val mat = ActorFlowMaterializer()

  class Setup(ops: List[Stage[_, _]] = List(fusing.Map({ x: Any ⇒ x }, stoppingDecider))) {
    val up = TestPublisher.manualProbe[Int]()
    val down = TestSubscriber.manualProbe[Int]
    private val props = ActorInterpreter.props(mat.settings, ops, mat).withDispatcher("akka.test.stream-dispatcher")
    val actor = system.actorOf(props)
    val processor = ActorProcessorFactory[Int, Int](actor)
  }

  "An ActorInterpreter" must {

    "pass along early cancellation" in new Setup {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      sub.cancel()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

    "heed cancellation signal while large demand is outstanding" in {
      val latch = TestLatch()
      val infinite = new PushPullStage[Int, Int] {
        override def onPush(elem: Int, ctx: Context[Int]) = ???
        override def onPull(ctx: Context[Int]) = {
          Await.ready(latch, 5.seconds)
          ctx.push(42)
        }
      }
      val N = system.settings.config.getInt("akka.stream.materializer.output-burst-limit")

      new Setup(infinite :: Nil) {
        processor.subscribe(down)
        val sub = down.expectSubscription()
        up.subscribe(processor)
        val upsub = up.expectSubscription()
        sub.request(100000000)
        sub.cancel()
        watch(actor)
        latch.countDown()
        for (i ← 1 to N) withClue(s"iteration $i: ") {
          try down.expectNext(42) catch { case e: Throwable ⇒ fail(e) }
        }
        // now cancellation request is processed
        down.expectNoMsg(500.millis)
        upsub.expectCancellation()
        expectTerminated(actor)
      }
    }

    "heed upstream failure while large demand is outstanding" in {
      val latch = TestLatch()
      val infinite = new PushPullStage[Int, Int] {
        override def onPush(elem: Int, ctx: Context[Int]) = ???
        override def onPull(ctx: Context[Int]) = {
          Await.ready(latch, 5.seconds)
          ctx.push(42)
        }
      }
      val N = system.settings.config.getInt("akka.stream.materializer.output-burst-limit")

      new Setup(infinite :: Nil) {
        processor.subscribe(down)
        val sub = down.expectSubscription()
        up.subscribe(processor)
        val upsub = up.expectSubscription()
        sub.request(100000000)
        val ex = new Exception("FAIL!")
        upsub.sendError(ex)
        latch.countDown()
        for (i ← 1 to N) withClue(s"iteration $i: ") {
          try down.expectNext(42) catch { case e: Throwable ⇒ fail(e) }
        }
        down.expectError(ex)
      }
    }

    "hold back upstream completion while large demand is outstanding" in {
      val latch = TestLatch()
      val N = 3 * system.settings.config.getInt("akka.stream.materializer.output-burst-limit")
      val infinite = new PushPullStage[Int, Int] {
        private var remaining = N
        override def onPush(elem: Int, ctx: Context[Int]) = ???
        override def onPull(ctx: Context[Int]) = {
          Await.ready(latch, 5.seconds)
          remaining -= 1
          if (remaining >= 0) ctx.push(42)
          else ctx.finish()
        }
        override def onUpstreamFinish(ctx: Context[Int]) = {
          if (remaining > 0) ctx.absorbTermination()
          else ctx.finish()
        }
      }

      new Setup(infinite :: Nil) {
        processor.subscribe(down)
        val sub = down.expectSubscription()
        up.subscribe(processor)
        val upsub = up.expectSubscription()
        sub.request(100000000)
        upsub.sendComplete()
        latch.countDown()
        for (i ← 1 to N) withClue(s"iteration $i: ") {
          try down.expectNext(42) catch { case e: Throwable ⇒ fail(e) }
        }
        down.expectComplete()
      }
    }

    "satisfy large demand" in largeDemand(0)
    "satisfy larger demand" in largeDemand(1)

    "handle spec violations" in {
      a[AbruptTerminationException] should be thrownBy {
        Await.result(
          Source(new Publisher[String] {
            def subscribe(s: Subscriber[_ >: String]) = {
              s.onSubscribe(new Subscription {
                def cancel() = ()
                def request(n: Long) = sys.error("test error")
              })
            }
          }).runFold("")(_ + _),
          3.seconds)
      }
    }

    def largeDemand(extra: Int): Unit = {
      val N = 3 * system.settings.config.getInt("akka.stream.materializer.output-burst-limit")
      val large = new PushPullStage[Int, Int] {
        private var remaining = N
        override def onPush(elem: Int, ctx: Context[Int]) = ???
        override def onPull(ctx: Context[Int]) = {
          remaining -= 1
          if (remaining >= 0) ctx.push(42)
          else ctx.finish()
        }
      }

      new Setup(large :: Nil) {
        processor.subscribe(down)
        val sub = down.expectSubscription()
        up.subscribe(processor)
        val upsub = up.expectSubscription()
        sub.request(100000000)
        watch(actor)
        for (i ← 1 to N) withClue(s"iteration $i: ") {
          try down.expectNext(42) catch { case e: Throwable ⇒ fail(e) }
        }
        down.expectComplete()
        upsub.expectCancellation()
        expectTerminated(actor)
      }
    }

  }

}
