/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import org.reactivestreams.api.Producer
import akka.stream.scaladsl.Flow

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowSplitWhenSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2))

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

  class SubstreamsSupport(splitWhen: Int = 3, elementCount: Int = 6) {
    val source = Flow((1 to elementCount).iterator).toProducer(materializer)
    val groupStream = Flow(source).splitWhen(_ == splitWhen).toProducer(materializer)
    val masterConsumer = StreamTestKit.consumerProbe[Producer[Int]]

    groupStream.produceTo(masterConsumer)
    val masterSubscription = masterConsumer.expectSubscription()

    def getSubproducer(): Producer[Int] = {
      masterSubscription.requestMore(1)
      expectSubproducer()
    }

    def expectSubproducer(): Producer[Int] = {
      val substream = masterConsumer.expectNext()
      substream
    }

  }

  "splitWhen" must {

    "work in the happy case" in new SubstreamsSupport(elementCount = 4) {
      val s1 = StreamPuppet(getSubproducer())
      masterConsumer.expectNoMsg(100.millis)

      s1.requestMore(2)
      s1.expectNext(1)
      s1.expectNext(2)
      s1.expectComplete()

      val s2 = StreamPuppet(getSubproducer())
      masterConsumer.expectComplete()

      s2.requestMore(1)
      s2.expectNext(3)
      s2.expectNoMsg(100.millis)

      s2.requestMore(1)
      s2.expectNext(4)
      s2.expectComplete()

    }

    "support cancelling substreams" in new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
      val s1 = StreamPuppet(getSubproducer())
      s1.cancel()
      val s2 = StreamPuppet(getSubproducer())

      s2.requestMore(4)
      s2.expectNext(5)
      s2.expectNext(6)
      s2.expectNext(7)
      s2.expectNext(8)
      s2.expectComplete()

      masterConsumer.expectComplete()
    }

    "support cancelling the master stream" in new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
      val s1 = StreamPuppet(getSubproducer())
      masterSubscription.cancel()
      s1.requestMore(4)
      s1.expectNext(1)
      s1.expectNext(2)
      s1.expectNext(3)
      s1.expectNext(4)
      s1.expectComplete()
    }

  }

}
