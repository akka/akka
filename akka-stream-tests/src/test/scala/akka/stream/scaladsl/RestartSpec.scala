/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.stream.scaladsl.RestartWithBackoffFlow.Delay
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.{ ActorMaterializer, Attributes, OverflowStrategy }
import akka.testkit.{ DefaultTimeout, TestDuration }
import akka.{ Done, NotUsed }

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class RestartSpec extends StreamSpec(Map("akka.test.single-expect-default" -> "10s")) with DefaultTimeout {

  implicit val mat = ActorMaterializer()

  import system.dispatcher

  private val shortMinBackoff = 10.millis
  private val shortMaxBackoff = 20.millis
  private val minBackoff = 1.second.dilated
  private val maxBackoff = 3.seconds.dilated

  "A restart with backoff source" should {
    "run normally" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val probe = RestartSource.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val probe = RestartSource.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val probe = RestartSource.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b"))
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")

      // There should be a delay of at least minBackoff before we receive the element after restart
      val deadline = (minBackoff - 1.millis).fromNow
      probe.request(1)

      probe.expectNext("a")
      deadline.isOverdue() should be(true)

      created.get() should ===(2)

      probe.cancel()
    }

    "reset exponential backoff back to minimum when source runs for at least minimum backoff without completing" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b"))
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")
      // There should be minBackoff delay
      probe.requestNext("a")
      probe.requestNext("b")
      probe.request(1)
      // The probe should now be backing off again with with increased backoff

      // Now wait for the delay to pass, then it will start the new source, we also want to wait for the
      // subsequent backoff to pass, so it resets the restart count
      Thread.sleep((minBackoff + (minBackoff * 2) + minBackoff + 500.millis).toMillis)

      probe.expectNext("a")
      probe.requestNext("b")

      // We should have reset, so the restart delay should be back, ie we should receive the
      // next element within < 2 * minBackoff
      probe.requestNext(2 * minBackoff - 10.milliseconds) should ===("a")

      created.get() should ===(4)

      probe.cancel()
    }

    "cancel the currently running source when cancelled" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val promise = Promise[Done]()
      val probe = RestartSource.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val probe = RestartSource.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Source.single("a")
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.request(1)
      // Should be backing off now
      probe.cancel()

      // Wait to ensure it isn't restarted
      Thread.sleep((minBackoff + 100.millis).toMillis)
      created.get() should ===(1)
    }

    "stop on completion if it should only be restarted in failures" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.onFailuresWithBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Source(List("a", "b", "c"))
          .map {
            case "c"   ⇒ if (created.get() == 1) throw TE("failed") else "c"
            case other ⇒ other
          }
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("b")
      // will fail, and will restart
      probe.requestNext("a")
      probe.requestNext("b")
      probe.requestNext("c")
      probe.expectComplete()

      created.get() should ===(2)

      probe.cancel()
    }

    "restart on failure when only due to failures should be restarted" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.onFailuresWithBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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

    "not restart the source when maxRestarts is reached" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(shortMinBackoff, shortMaxBackoff, 0, maxRestarts = 1) { () ⇒
        created.incrementAndGet()
        Source.single("a")
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      probe.requestNext("a")
      probe.expectComplete()

      created.get() should ===(2)

      probe.cancel()
    }

    "reset maxRestarts when source runs for at least minimum backoff without completing" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val probe = RestartSource.withBackoff(minBackoff, maxBackoff, 0, maxRestarts = 2) { () ⇒
        created.incrementAndGet()
        Source(List("a"))
      }.runWith(TestSink.probe)

      probe.requestNext("a")
      // There should be minBackoff delay
      probe.requestNext("a")
      // The probe should now be backing off again with with increased backoff

      // Now wait for the delay to pass, then it will start the new source, we also want to wait for the
      // subsequent backoff to pass
      Thread.sleep((minBackoff + (minBackoff * 2) + minBackoff + 500.millis).toMillis)

      probe.requestNext("a")
      // We now are able to trigger the third restart, since enough time has elapsed to reset the counter
      probe.requestNext("a")

      created.get() should ===(4)

      probe.cancel()
    }
  }

  "A restart with backoff sink" should {
    "run normally" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val result = Promise[Seq[String]]()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("a")
      sinkProbe.requestNext("a")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      probe.sendNext("b")
      val deadline = (minBackoff - 1.millis).fromNow
      sinkProbe.request(1)
      sinkProbe.expectNext("b")
      deadline.isOverdue() should be(true)

      created.get() should ===(2)

      sinkProbe.cancel()
      probe.sendComplete()
    }

    "reset exponential backoff back to minimum when sink runs for at least minimum backoff without completing" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("a")
      sinkProbe.requestNext("a")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      // There should be a minBackoff delay
      probe.sendNext("b")
      sinkProbe.requestNext("b")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      sinkProbe.request(1)
      // The probe should now be backing off for 2 * minBackoff

      // Now wait for the 2 * minBackoff delay to pass, then it will start the new source, we also want to wait for the
      // subsequent minBackoff min backoff to pass, so it resets the restart count
      Thread.sleep((minBackoff + (minBackoff * 2) + minBackoff + 500.millis).toMillis)

      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")

      // We should have reset, so the restart delay should be back to minBackoff, ie we should definitely receive the
      // next element within < 2 * minBackoff
      probe.sendNext("c")
      sinkProbe.request(1)
      sinkProbe.expectNext((2 * minBackoff) - 10.millis, "c")

      created.get() should ===(4)

      sinkProbe.cancel()
      probe.sendComplete()
    }

    "not restart the sink when completed while backing off" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(minBackoff, maxBackoff, 0) { () ⇒
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
      Thread.sleep((minBackoff + 100.millis).toMillis)
      created.get() should ===(1)

      sinkProbe.cancel()
    }

    "not restart the sink when maxRestarts is reached" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(shortMinBackoff, shortMaxBackoff, 0, maxRestarts = 1) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")

      probe.expectCancellation()

      created.get() should ===(2)

      sinkProbe.cancel()
      probe.sendComplete()
    }

    "reset maxRestarts when sink runs for at least minimum backoff without completing" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (queue, sinkProbe) = TestSource.probe[String].toMat(TestSink.probe)(Keep.both).run()
      val probe = TestSource.probe[String].toMat(RestartSink.withBackoff(minBackoff, maxBackoff, 0, maxRestarts = 2) { () ⇒
        created.incrementAndGet()
        Flow[String].takeWhile(_ != "cancel", inclusive = true)
          .to(Sink.foreach(queue.sendNext))
      })(Keep.left).run()

      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      // There should be a minBackoff delay
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")
      // The probe should now be backing off for 2 * minBackoff

      // Now wait for the 2 * minBackoff delay to pass, then it will start the new source, we also want to wait for the
      // subsequent minBackoff min backoff to pass, so it resets the restart count
      Thread.sleep((minBackoff + (minBackoff * 2) + minBackoff + 500.millis).toMillis)

      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")

      // We now are able to trigger the third restart, since enough time has elapsed to reset the counter
      probe.sendNext("cancel")
      sinkProbe.requestNext("cancel")

      created.get() should ===(4)

      sinkProbe.cancel()
      probe.sendComplete()
    }
  }

  "A restart with backoff flow" should {

    // helps reuse all the setupFlow code for both methods: withBackoff, and onlyOnFailuresWithBackoff
    def RestartFlowFactory[In, Out](onlyOnFailures: Boolean): (FiniteDuration, FiniteDuration, Double, Int) ⇒ (() ⇒ Flow[In, Out, _]) ⇒ Flow[In, Out, NotUsed] = if (onlyOnFailures) {
      RestartFlow.onFailuresWithBackoff
    } else {
      // choose the correct backoff method
      (minBackoff, maxBackoff, randomFactor, maxRestarts) ⇒ RestartFlow.withBackoff(minBackoff, maxBackoff, randomFactor, maxRestarts)
    }

    def setupFlow(minBackoff: FiniteDuration, maxBackoff: FiniteDuration, maxRestarts: Int = -1, onlyOnFailures: Boolean = false) = {
      val created = new AtomicInteger()

      val (flowInSource: TestPublisher.Probe[String], flowInProbe: TestSubscriber.Probe[String]) = TestSource.probe[String]
        .buffer(4, OverflowStrategy.backpressure)
        .toMat(TestSink.probe)(Keep.both).run()

      val (flowOutProbe: TestPublisher.Probe[String], flowOutSource: Source[String, NotUsed]) = TestSource.probe[String].toMat(BroadcastHub.sink)(Keep.both).run()

      // We can't just use ordinary probes here because we're expecting them to get started/restarted. Instead, we
      // simply use the probes as a message bus for feeding and capturing events.
      val (source, sink) = TestSource.probe[String].viaMat(RestartFlowFactory(onlyOnFailures)(minBackoff, maxBackoff, 0, maxRestarts) { () ⇒
        created.incrementAndGet()
        Flow.fromSinkAndSource(
          Flow[String]
            .takeWhile(_ != "cancel")
            .map {
              case "in error" ⇒ throw TE("in error")
              case other      ⇒ other
            }
            .to(Sink.foreach(flowInSource.sendNext)
              .mapMaterializedValue(_.onComplete {
                case Success(_) ⇒ flowInSource.sendNext("in complete")
                case Failure(_) ⇒ flowInSource.sendNext("in error")
              })),
          flowOutSource
            .takeWhile(_ != "complete")
            .map {
              case "error" ⇒ throw TE("error")
              case other   ⇒ other
            }.watchTermination()((_, term) ⇒
              term.foreach(_ ⇒ {
                flowInSource.sendNext("out complete")
              })))
      })(Keep.left).toMat(TestSink.probe[String])(Keep.both).run()

      (created, source, flowInProbe, flowOutProbe, sink)
    }

    "run normally" in assertAllStagesStopped {
      val created = new AtomicInteger()
      val (source, sink) = TestSource.probe[String].viaMat(RestartFlow.withBackoff(shortMinBackoff, shortMaxBackoff, 0) { () ⇒
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
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff)

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
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff)

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
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff)

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
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(minBackoff, maxBackoff)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      // we need to start counting time before we issue the cancel signal,
      // as starting the counter anywhere after the cancel signal, might not
      // capture all of the time, that has been spent for the backoff.
      val deadline = minBackoff.fromNow

      source.sendNext("cancel")
      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      Seq(flowInProbe.expectNext(), flowInProbe.expectNext()) should contain only ("in complete", "out complete")

      source.sendNext("c")
      flowInProbe.request(1)
      flowInProbe.expectNext("c")
      deadline.isOverdue() should be(true)

      created.get() should ===(2)
    }

    "continue running flow out port after in has been sent completion" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, maxBackoff)

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
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, maxBackoff)

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

    "not restart on completion when maxRestarts is reached" in {
      val (created, _, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff, maxRestarts = 1)

      sink.request(1)
      flowOutProbe.sendNext("complete")

      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      Seq(flowInProbe.expectNext(), flowInProbe.expectNext()) should contain only ("in complete", "out complete")

      // and it should restart
      sink.request(1)
      flowOutProbe.sendNext("complete")

      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      flowInProbe.expectNext("out complete")
      flowInProbe.expectNoMessage(shortMinBackoff * 3)
      sink.expectComplete()

      created.get() should ===(2)
    }

    // onlyOnFailures -->
    "stop on cancellation when using onlyOnFailuresWithBackoff" in {
      val onlyOnFailures = true
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff, -1, onlyOnFailures)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      source.sendNext("cancel")
      // This will complete the flow in probe and cancel the flow out probe
      flowInProbe.request(2)
      flowInProbe.expectNext("in complete")

      source.expectCancellation()

      created.get() should ===(1)
    }

    "stop on completion when using onlyOnFailuresWithBackoff" in {
      val onlyOnFailures = true
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff, -1, onlyOnFailures)

      source.sendNext("a")
      flowInProbe.requestNext("a")
      flowOutProbe.sendNext("b")
      sink.requestNext("b")

      flowOutProbe.sendNext("complete")
      sink.request(1)
      sink.expectComplete()

      created.get() should ===(1)
    }

    "restart on failure when using onlyOnFailuresWithBackoff" in {
      val (created, source, flowInProbe, flowOutProbe, sink) = setupFlow(shortMinBackoff, shortMaxBackoff, -1, true)

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

    "onFailuresWithBackoff, wrapped flow exception should restart configured times" in {
      val flowCreations = new AtomicInteger(0)
      val failsSomeTimes = Flow[Int].map { i ⇒
        if (i % 3 == 0) throw TE("fail") else i
      }

      val restartOnFailures =
        RestartFlow.onFailuresWithBackoff(1.second, 2.seconds, 0.2, 2)(() ⇒ {
          flowCreations.incrementAndGet()
          failsSomeTimes
        }).addAttributes(Attributes(Delay(100.millis)))

      val elements = Source(1 to 7)
        .via(restartOnFailures)
        .runWith(Sink.seq).futureValue
      elements shouldEqual List(1, 2, 4, 5, 7)
      flowCreations.get() shouldEqual 3
    }
  }
}
