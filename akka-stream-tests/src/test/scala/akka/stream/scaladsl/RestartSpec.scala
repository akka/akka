/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.stream.ActorMaterializer
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.{ TE, assertAllStagesStopped }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit.DefaultTimeout

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class RestartSpec extends StreamSpec with DefaultTimeout {

  implicit val mat = ActorMaterializer()
  import system.dispatcher

  "A restart with backoff source" should {
    "run normally" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(10.millis, 20.millis, 0) { () ⇒
        created.incrementAndGet()
        Source.repeat("a")
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("a")
      probe.requestNext("a")
      probe.requestNext("a")
      probe.requestNext("a")

      created.get() should ===(1)

      probe.cancel()
    }

    "restart on completion" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(10.millis, 20.millis, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b"))
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")
      probe.requestNext("a")
      probe.requestNext("b")
      probe.requestNext("a")

      created.get() should ===(3)

      probe.cancel()
    }

    "restart on failure" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(10.millis, 20.millis, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b", "c"))
          .map {
            case "c"   ⇒ throw TE("failed")
            case other ⇒ other
          }
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")
      probe.requestNext("a")
      probe.requestNext("b")
      probe.requestNext("a")

      created.get() should ===(3)

      probe.cancel()
    }

    "backoff before restart" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(200.millis, 1.second, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b"))
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")
      probe.request(1)
      // There should be a delay of at least 200ms before we receive the element, wait for 100ms.
      val deadline = 100.millis.fromNow
      // But the delay shouldn't be more than 300ms.
      probe.expectNext(300.milliseconds, "a")
      deadline.isOverdue() should be(true)

      created.get() should ===(2)

      probe.cancel()
    }

    "reset exponential backoff back to minimum when source runs for at least minimum backoff without completing" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(200.millis, 2.seconds, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b"))
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")
      // There should be a 200ms delay
      probe.requestNext("a")
      probe.requestNext("b")
      probe.request(1)
      // The probe should now be backing off for 400ms

      // Now wait for the 400ms delay to pass, then it will start the new source, we also want to wait for the
      // subsequent 200ms min backoff to pass, so it resets the restart count
      Thread.sleep(700)

      probe.expectNext("a")
      probe.requestNext("b")

      // We should have reset, so the restart delay should be back to 200ms, ie we should definitely receive the
      // next element within 300ms
      probe.requestNext(300.milliseconds) should ===("a")

      created.get() should ===(4)

      probe.cancel()
    }

    "cancel the currently running source when cancelled" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val promise = Promise[Done]()
      val probe = RestartSource.withBackoff(10.millis, 2.seconds, 0) { () ⇒
        created.incrementAndGet()
        Source.repeat("a").watchTermination() { (_, term) ⇒
          promise.completeWith(term)
        }
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.cancel()

      promise.future.futureValue should ===(Done)

      // Wait to ensure it isn't restarted
      Thread.sleep(200)
      created.get() should ===(1)
    }

    "not restart the source when cancelled while backing off" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(200.millis, 2.seconds, 0) { () ⇒
        created.incrementAndGet()
        Source.single("a")
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.request(1)
      // Should be backing off now
      probe.cancel()

      // Wait to ensure it isn't restarted
      Thread.sleep(300)
      created.get() should ===(1)
    }
  }

  "A restart with backoff sink" should {
    "run normally" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val result = Promise[Seq[String]]()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(10.millis, 20.millis, 0) { () ⇒
        created.incrementAndGet()
        Sink.seq.mapMaterializedValue(result.completeWith)
      })(Keep.left).run()

      probe.sendNext("a")
      probe.sendNext("b")
      probe.sendNext("c")
      probe.sendComplete()

      result.future.futureValue should contain inOrderOnly ("a", "b", "c")
      created.get() should ===(1)
    }

    "restart on cancellation" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(10.millis, 20.millis, 0) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("a")
      sinkProbe.requestNext("a")
      probe.sendNext("b")
      sinkProbe.requestNext("b")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      probe.sendNext("c")
      sinkProbe.requestNext("c")

      created.get() should ===(2)

      sinkProbe.cancel()
      probe.sendComplete()
    }

    "backoff before restart" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(200.millis, 2.seconds, 0) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("a")
      sinkProbe.requestNext("a")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      probe.sendNext("b")
      sinkProbe.request(1)
      val deadline = 100.millis.fromNow
      sinkProbe.expectNext(300.millis, "b")
      deadline.isOverdue() should be(true)

      created.get() should ===(2)

      sinkProbe.cancel()
      probe.sendComplete()
    }

    "reset exponential backoff back to minimum when sink runs for at least minimum backoff without completing" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(200.millis, 2.seconds, 0) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("a")
      sinkProbe.requestNext("a")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      // There should be a 200ms delay
      probe.sendNext("b")
      sinkProbe.requestNext("b")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      sinkProbe.request(1)
      // The probe should now be backing off for 400ms

      // Now wait for the 400ms delay to pass, then it will start the new source, we also want to wait for the
      // subsequent 200ms min backoff to pass, so it resets the restart count
      Thread.sleep(700)

      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")

      // We should have reset, so the restart delay should be back to 200ms, ie we should definitely receive the
      // next element within 300ms
      probe.sendNext("c")
      sinkProbe.request(1)
      sinkProbe.expectNext(300.milliseconds, "c")

      created.get() should ===(4)

      sinkProbe.cancel()
      probe.sendComplete()
    }

    "not restart the sink when completed while backing off" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(200.millis, 2.seconds, 0) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("a")
      sinkProbe.requestNext("a")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      // Should be backing off now
      probe.sendComplete()

      // Wait to ensure it isn't restarted
      Thread.sleep(300)
      created.get() should ===(1)

      sinkProbe.cancel()
    }
  }

  "A restart with backoff flow" should {

    def setupFlow(minBackoff: FiniteDuration, maxBackoff: FiniteDuration) = {
      val created = new AtomicInteger()
      val (flowInSource, flowInProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val (flowOutProbe, flowOutSource) = TestSource.probe[String].toMat(BroadcastHub.sink)(Keep.both).run()

      // We can't just use ordinary probes here because we're expecting them to get started/restarted. Instead, we
      // simply use the probes as a message bus for feeding and capturing events.
      val (source, sink) = TestSource.probe[String].viaMat(RestartFlow.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Flow.fromSinkAndSource(
          Flow[String].takeWhile(_ != "cancel").to(Sink.foreach(flowInSource.sendNext).mapMaterializedValue(_.onComplete {
            case Success(_) ⇒ flowInSource.sendNext("in complete")
            case Failure(_) ⇒ flowInSource.sendNext("in error")
          })),
          flowOutSource.takeWhile(_ != "complete").map {
            case "error" ⇒ throw TE("error")
            case other   ⇒ other
          }.watchTermination()((_, term) ⇒
            term.foreach(_ ⇒ {
              flowInSource.sendNext("out complete")
            })
          )
        )
      })(Keep.left).toMat(TestSink.probe[String])(Keep.both).run()

      (created, source, flowInProbe, flowOutProbe, sink)
    }

    "run normally" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (source, sink) = TestSource.probe[String].viaMat(RestartFlow.withBackoff(10.millis, 20.millis, 0) { () ⇒
        created.incrementAndGet()
        Flow[String]
      })(Keep.left).toMat(TestSink.probe[String])(Keep.both).run()

      source.sendNext("a")
      sink.requestNext("a")
      source.sendNext("b")
      sink.requestNext("b")

      created.get() should ===(1)

      source.sendComplete()
    }

    "restart on cancellation" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(10.millis, 20.millis)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      source.sendNext("cancel")
      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      Seq(flowInProbe.expectNext(), flowInProbe.expectNext()) should contain only ("in complete", "out complete")

      // and it should restart
      source.sendNext("c")
      flowInProbe.requestNext("c")
      flowOutProbe.sendNext("d")
      sink.requestNext("d")

      created.get() should ===(2)
    }

    "restart on completion" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(10.millis, 20.millis)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      sink.request(1)
      flowOutProbe.sendNext("complete")

      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      Seq(flowInProbe.expectNext(), flowInProbe.expectNext()) should contain only ("in complete", "out complete")

      // and it should restart
      source.sendNext("c")
      flowInProbe.requestNext("c")
      flowOutProbe.sendNext("d")
      sink.requestNext("d")

      created.get() should ===(2)
    }

    "restart on failure" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(10.millis, 20.millis)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      sink.request(1)
      flowOutProbe.sendNext("error")

      // This should complete the in probe
      flowInProbe.requestNext("in complete")

      // and it should restart
      source.sendNext("c")
      flowInProbe.requestNext("c")
      flowOutProbe.sendNext("d")
      sink.requestNext("d")

      created.get() should ===(2)
    }

    "backoff before restart" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(200.millis, 2.seconds)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      source.sendNext("cancel")
      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      Seq(flowInProbe.expectNext(), flowInProbe.expectNext()) should contain only ("in complete", "out complete")

      source.sendNext("c")
      flowInProbe.request(1)
      val deadline = 100.millis.fromNow
      flowInProbe.expectNext(300.millis, "c")
      deadline.isOverdue() should be(true)

      created.get() should ===(2)
    }

    "continue running flow out port after in has been sent completion" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(20.millis, 40.seconds)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      source.sendComplete()
      flowInProbe.requestNext("in complete")

      flowOutProbe.sendNext("c")
      sink.requestNext("c")
      flowOutProbe.sendNext("d")
      sink.requestNext("d")

      sink.request(1)
      flowOutProbe.sendComplete()
      flowInProbe.requestNext("out complete")
      sink.expectComplete()

      created.get() should ===(1)
    }

    "continue running flow in port after out has been cancelled" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(20.millis, 40.seconds)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      sink.cancel()
      flowInProbe.requestNext("out complete")

      source.sendNext("c")
      flowInProbe.requestNext("c")
      source.sendNext("d")
      flowInProbe.requestNext("d")

      source.sendNext("cancel")
      flowInProbe.requestNext("in complete")
      source.expectCancellation()

      created.get() should ===(1)
    }

  }

}
