/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Flow

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowSplitWhenSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

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
    val source = Flow((1 to elementCount).iterator).toPublisher(materializer)
    val groupStream = Flow(source).splitWhen(_ == splitWhen).toPublisher(materializer)
    val masterSubscriber = StreamTestKit.SubscriberProbe[Publisher[Int]]()

    groupStream.subscribe(masterSubscriber)
    val masterSubscription = masterSubscriber.expectSubscription()

    def getSubPublisher(): Publisher[Int] = {
      masterSubscription.request(1)
      expectSubPublisher()
    }

    def expectSubPublisher(): Publisher[Int] = {
      val substream = masterSubscriber.expectNext()
      substream
    }

  }

  "splitWhen" must {

    "work in the happy case" in new SubstreamsSupport(elementCount = 4) {
      val s1 = StreamPuppet(getSubPublisher())
      masterSubscriber.expectNoMsg(100.millis)

      s1.request(2)
      s1.expectNext(1)
      s1.expectNext(2)
      s1.expectComplete()

      val s2 = StreamPuppet(getSubPublisher())
      masterSubscriber.expectComplete()

      s2.request(1)
      s2.expectNext(3)
      s2.expectNoMsg(100.millis)

      s2.request(1)
      s2.expectNext(4)
      s2.expectComplete()

    }

    "support cancelling substreams" in new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
      val s1 = StreamPuppet(getSubPublisher())
      s1.cancel()
      val s2 = StreamPuppet(getSubPublisher())

      s2.request(4)
      s2.expectNext(5)
      s2.expectNext(6)
      s2.expectNext(7)
      s2.expectNext(8)
      s2.expectComplete()

      masterSubscriber.expectComplete()
    }

    "support cancelling the master stream" in new SubstreamsSupport(splitWhen = 5, elementCount = 8) {
      val s1 = StreamPuppet(getSubPublisher())
      masterSubscription.cancel()
      s1.request(4)
      s1.expectNext(1)
      s1.expectNext(2)
      s1.expectNext(3)
      s1.expectNext(4)
      s1.expectComplete()
    }

  }

}
