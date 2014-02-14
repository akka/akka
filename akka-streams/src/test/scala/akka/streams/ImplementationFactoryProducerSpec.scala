package akka.streams

import _root_.akka.streams.testkit.TestKit
import scala.concurrent.duration._
import _root_.akka.testkit.duration2TestDuration
import Operation._

trait ImplementationFactoryProducerSpec extends ImplementationFactorySpec {
  "A producer built by an ImplementationFactory" - {
    "for SingletonSource" in new InitializedChainSetup(SingletonSource("test")) {
      downstreamSubscription.requestMore(2)
      downstream.expectNext("test")
      downstream.expectComplete()
    }
    "for FromIterableSource" - {
      "empty" in pendingUntilFixed(new InitializedChainSetup(FromIterableSource(Seq())) {
        downstream.expectComplete()
      })
      "with elements" in new InitializedChainSetup(FromIterableSource(Seq(1, 2, 3))) {
        downstreamSubscription.requestMore(2)
        downstream.expectNext(1)
        downstream.expectNext(2)
        downstream.expectNoMsg(100.millis.dilated)
        downstreamSubscription.requestMore(1)
        downstream.expectNext(3)
        downstream.expectComplete()
      }
    }
    "for FromProducerSource" in {
      val producerProbe = TestKit.producerProbe[String]()
      new InitializedChainSetup[String](FromProducerSource(producerProbe)) {
        downstreamSubscription.requestMore(2)
        val upstreamSubscription = producerProbe.expectSubscription()
        // default fan-out-box with buffer size 1 translates any incoming request to one upstream
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("text")
        upstreamSubscription.expectRequestMore(1)
        downstream.expectNext("text")
        upstreamSubscription.sendNext("more text")
        upstreamSubscription.sendComplete()
        downstream.expectNext("more text")
        downstream.expectComplete()
      }
    }
    "for MappedSource" in new InitializedChainSetup(FromIterableSource(Seq(1, 2, 3)).map(_ + 1)) {
      downstreamSubscription.requestMore(2)
      downstream.expectNext(2)
      downstream.expectNext(3)
      downstream.expectNoMsg(100.millis.dilated)
      downstreamSubscription.requestMore(1)
      downstream.expectNext(4)
      downstream.expectComplete()
    }

    "support multiple subscribers" - {
      "single subscriber cancels subscription while receiving data" in pending
      "properly serve multiple subscribers" in pending
    }
  }

  class InitializedChainSetup[O](source: Source[O])(implicit factory: ImplementationFactory) {
    val producer = source.create()

    val downstream = TestKit.consumerProbe[O]()
    producer.link(downstream)
    val downstreamSubscription = downstream.expectSubscription()
  }
}
