/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ AkkaSpec, ChainSetup, StreamTestKit }
import org.reactivestreams.api.Producer

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NoStackTrace

class FlowMergeAllSpec extends AkkaSpec {

  val ms = MaterializerSettings(
    initialInputBufferSize = 1,
    maximumInputBufferSize = 1,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 1,
    dispatcher = "akka.test.stream-dispatcher")
  val m = FlowMaterializer(ms)
  val testException = new Exception("test") with NoStackTrace
  class MergeChain(maxInputs: Int = 8) extends ChainSetup[Producer[Int], Int](_.flatten(FlattenStrategy.merge(maxInputs)), ms)

  "MergeAll" must {
    "work in happy case like concatAll" in {
      val s1 = Flow((1 to 2).iterator).toProducer(m)
      val s2 = Flow(List.empty[Int]).toProducer(m)
      val s3 = Flow(List(3)).toProducer(m)
      val s4 = Flow((4 to 6).iterator).toProducer(m)
      val s5 = Flow((7 to 10).iterator).toProducer(m)

      val main: Flow[Producer[Int]] = Flow(List(s1, s2, s3, s4, s5))

      val result = Await.result(main.flatten(FlattenStrategy.merge()).grouped(10).toFuture(m), 3.seconds).toSet
      result should be(1 to 10 toSet)
    }

    "fetch messages from substreams" in {
      new MergeChain {
        downstreamSubscription.requestMore(5)
        upstreamSubscription.expectRequestMore()
        upstreamSubscription.sendNext(Flow(List(1, 2, 3)).toProducer(m))
        downstream.expectNext(1, 2, 3)
        downstream.expectNoMsg(200 millis)

        upstreamSubscription.sendNext(Flow(List(4, 5, 6)).toProducer(m))
        downstream.expectNext(4, 5)
        downstream.expectNoMsg(200 millis)

        downstreamSubscription.requestMore(5)
        downstream.expectNext(6)
        upstreamSubscription.expectRequestMore()

        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
    }

    "react to error in substeram and cause master stream to error" in {
      new MergeChain {
        downstreamSubscription.requestMore(1)
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext(Flow[Int](() ⇒ throw testException).toProducer(m))
        upstreamSubscription.expectCancellation()
        downstream.expectError(testException)
      }
    }

    "react to error in master stream and cause it to error" in {
      new MergeChain {
        downstreamSubscription.requestMore(5)
        upstreamSubscription.expectRequestMore()
        upstreamSubscription.sendNext(Flow(List(1, 2, 3).iterator).toProducer(m))
        upstreamSubscription.sendError(testException)
        downstream.expectError(testException)
      }
    }

    "stop requesting substreams when substreams limit reached" in {
      new MergeChain(maxInputs = 1) {
        downstreamSubscription.requestMore(1)

        val substream = StreamTestKit.producerProbe[Int]()
        val substreamCached = StreamTestKit.producerProbe[Int]()
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext(substream)

        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext(substreamCached)

        val substreamSubscription = substream.expectSubscription()
        substreamCached.expectNoMsg()
        upstream.expectNoMsg()
      }
    }

    "process streams one by one when max simultaneous streams is one" in {
      val s1 = Flow((1 to 2).iterator).toProducer(m)
      val s2 = Flow(List.empty[Int]).toProducer(m)
      val s3 = Flow(List(3)).toProducer(m)
      val s4 = Flow((4 to 6).iterator).toProducer(m)
      val s5 = Flow((7 to 10).iterator).toProducer(m)

      val main: Flow[Producer[Int]] = Flow(List(s1, s2, s3, s4, s5))

      val result = Await.result(main.flatten(FlattenStrategy.merge(1)).grouped(10).toFuture(m), 3.seconds)
      result should be(1 to 10)
    }

    "handle closed producers" in {
      new MergeChain {
        downstreamSubscription.requestMore(50)
        val producers = 1 to 3 map { _ ⇒ StreamTestKit.producerProbe[Int] }
        var upstreams = producers.map { _producer ⇒
          upstreamSubscription.sendNext(_producer)
          _producer.expectSubscription()
        }
        upstreamSubscription.sendComplete()

        upstreams.zipWithIndex.foreach { case (us, n) ⇒ us.sendNext(n * 10) }
        downstream.expectNext(0, 10, 20)

        upstreams.last.sendComplete()
        upstreams = upstreams.dropRight(1)
        upstreams.zipWithIndex.foreach { case (us, n) ⇒ us.sendNext(n * 10 + 1) }

        downstream.expectNext(1, 11)

        upstreams.last.sendComplete()
        upstreams = upstreams.dropRight(1)
        upstreams.zipWithIndex.foreach { case (us, n) ⇒ us.sendNext(n * 10 + 2) }

        downstream.expectNext(2)

        upstreams.head.sendComplete()
        downstream.expectComplete()
      }
    }
  }
}
