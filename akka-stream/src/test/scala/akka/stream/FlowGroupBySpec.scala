/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit._
import akka.testkit.AkkaSpec
import org.reactivestreams.api.Producer
import akka.stream.impl.{ IteratorProducer, ActorBasedProcessorGenerator }
import akka.stream.scala_api.Flow

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class StreamGroupBySpec extends AkkaSpec {

  val gen = new ActorBasedProcessorGenerator(GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2), system)

  case class StreamPuppet(p: Producer[Int]) {
    val probe = StreamTestKit.consumerProbe[Int]
    p.produceTo(probe)
    val subscription = probe.expectSubscription()

    def requestMore(demand: Int): Unit = subscription.requestMore(demand)
    def expectNext(elem: Int): Unit = probe.expectNext(elem)
    def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)
    def expectComplete(): Unit = probe.expectComplete()
    def cancel(): Unit = subscription.cancel()
  }

  class SubstreamsSupport(groupCount: Int = 2, elementCount: Int = 6) {
    val source = Flow((1 to elementCount).iterator).toProducer(gen)
    val groupStream = Flow(source).groupBy(_ % groupCount).toProducer(gen)
    val masterConsumer = StreamTestKit.consumerProbe[(Int, Producer[Int])]

    groupStream.produceTo(masterConsumer)
    val masterSubscription = masterConsumer.expectSubscription()

    def getSubproducer(expectedKey: Int): Producer[Int] = {
      masterSubscription.requestMore(1)
      expectSubproducer(expectedKey: Int)
    }

    def expectSubproducer(expectedKey: Int): Producer[Int] = {
      val (key, substream) = masterConsumer.expectNext()
      key should be(expectedKey)
      substream
    }

  }

  "groupBy" must {
    "work in the happy case" in new SubstreamsSupport(groupCount = 2) {
      val s1 = StreamPuppet(getSubproducer(1))
      masterConsumer.expectNoMsg(100.millis)

      s1.expectNoMsg(100.millis)
      s1.requestMore(1)
      s1.expectNext(1)
      s1.expectNoMsg(100.millis)

      val s2 = StreamPuppet(getSubproducer(0))
      masterConsumer.expectNoMsg(100.millis)

      s2.expectNoMsg(100.millis)
      s2.requestMore(2)
      s2.expectNext(2)
      s2.expectNext(4)

      s2.expectNoMsg(100.millis)

      s1.requestMore(1)
      s1.expectNext(3)

      s2.requestMore(1)
      s2.expectNext(6)
      s2.expectComplete()

      s1.requestMore(1)
      s1.expectNext(5)
      s1.expectComplete()

      masterConsumer.expectComplete()

    }

    "accept cancellation of substreams" in new SubstreamsSupport(groupCount = 2) {
      StreamPuppet(getSubproducer(1)).cancel()

      val substream = StreamPuppet(getSubproducer(0))
      masterConsumer.expectNoMsg(100.millis)
      substream.requestMore(2)
      substream.expectNext(2)
      substream.expectNext(4)
      substream.expectNoMsg(100.millis)

      substream.requestMore(2)
      substream.expectNext(6)
      substream.expectComplete()

      masterConsumer.expectComplete()

    }

    "accept cancellation of master stream when not consumed anything" in new SubstreamsSupport(groupCount = 2) {
      masterSubscription.cancel()
      masterConsumer.expectNoMsg(100.millis)
    }

    "accept cancellation of master stream when substreams are open" in new SubstreamsSupport(groupCount = 3, elementCount = 13) {
      pending
      //      val substream = StreamPuppet(getSubproducer(1))
      //
      //      substream.requestMore(1)
      //      substream.expectNext(1)
      //
      //      masterSubscription.cancel()
      //      masterConsumer.expectNoMsg(100.millis)
      //
      //      // Open substreams still work, others are discarded
      //      substream.requestMore(4)
      //      substream.expectNext(4)
      //      substream.expectNext(7)
      //      substream.expectNext(10)
      //      substream.expectNext(13)
      //      substream.expectComplete()
    }

    "work with fanout on substreams" in new SubstreamsSupport(groupCount = 2) {
      val substreamProducer = getSubproducer(1)
      getSubproducer(0)

      val substreamConsumer1 = StreamPuppet(substreamProducer)
      val substreamConsumer2 = StreamPuppet(substreamProducer)

      substreamConsumer1.requestMore(1)
      substreamConsumer1.expectNext(1)
      substreamConsumer2.requestMore(1)
      substreamConsumer2.expectNext(1)

      substreamConsumer1.requestMore(1)
      substreamConsumer1.expectNext(3)
      substreamConsumer2.requestMore(1)
      substreamConsumer2.expectNext(3)
    }

    "work with fanout on master stream" in {
      pending
    }

    "work with fanout on substreams and master stream" in {
      pending
    }

    "abort on onError from upstream" in {
      pending
    }
  }

}
