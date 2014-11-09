/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import org.reactivestreams.Publisher

class FlowSplitWhenSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  case class StreamPuppet(p: Publisher[Int]) {
    val probe = StreamTestKit.SubscriberProbe[Int]()
    p.subscribe(probe)
    val subscription = probe.expectSubscription()

    def request(demand: Int): Unit = subscription.request(demand)
    def expectNext(elem: Int): Unit = probe.expectNext(elem)
    def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)
    def expectComplete(): Unit = probe.expectComplete()
    def cancel(): Unit = subscription.cancel()
  }

  class SubstreamsSupport(splitWhen: Int = 3, elementCount: Int = 6) {
    val source = Source(1 to elementCount)
    val groupStream = source.splitWhen(_ == splitWhen).runWith(Sink.publisher)
    val masterSubscriber = StreamTestKit.SubscriberProbe[Source[Int]]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def getSubFlow(): Source[Int] = {
      masterSubscription.request(1)
      expectSubPublisher()
    }

    def expectSubPublisher(): Source[Int] = {
      val substream = masterSubscriber.expectNext()
      substream
    }

  }

  "splitWhen" must {

    "work in the happy case" in new SubstreamsSupport(elementCount = 4) {
      val s1 = StreamPuppet(getSubFlow().runWith(Sink.publisher))
      masterSubscriber.expectNoMsg(100.millis)

      s1.request(2)
      s1.expectNext(1)
      s1.expectNext(2)
      s1.request(1)
      s1.expectComplete()

      val s2 = StreamPuppet(getSubFlow().runWith(Sink.publisher))

      s2.request(1)
      s2.expectNext(3)
      s2.expectNoMsg(100.millis)

      s2.request(1)
      s2.expectNext(4)
      s2.request(1)
      s2.expectComplete()

      masterSubscriber.expectComplete()
    }

    "support cancelling substreams" in new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
      val s1 = StreamPuppet(getSubFlow().runWith(Sink.publisher))
      s1.cancel()
      val s2 = StreamPuppet(getSubFlow().runWith(Sink.publisher))

      s2.request(4)
      s2.expectNext(5)
      s2.expectNext(6)
      s2.expectNext(7)
      s2.expectNext(8)
      s2.request(1)
      s2.expectComplete()

      masterSubscription.request(1)
      masterSubscriber.expectComplete()
    }

    "support cancelling the master stream" in new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
      val s1 = StreamPuppet(getSubFlow().runWith(Sink.publisher))
      masterSubscription.cancel()
      s1.request(4)
      s1.expectNext(1)
      s1.expectNext(2)
      s1.expectNext(3)
      s1.expectNext(4)
      s1.request(1)
      s1.expectComplete()
    }

  }

}
