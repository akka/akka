package akka.streams

import org.scalatest.{ BeforeAndAfterAll, WordSpec, ShouldMatchers }
import rx.async.tck._
import akka.streams.testkit.TestKit
import akka.testkit.TestKitBase
import akka.actor.ActorSystem
import rx.async.api.{ Producer, Processor }

class OperationProcessorSpec extends WordSpec with TestKitBase with ShouldMatchers with BeforeAndAfterAll {
  implicit lazy val system = ActorSystem()
  implicit lazy val settings: ProcessorSettings = ProcessorSettings(system)

  "An OperationProcessor" should {
    "work uninitialized without publisher" when {
      "subscriber requests elements" in pending
      "subscriber cancels subscription and resubscribes" in pending
    }
    "work uninitialized without subscriber" when {
      "publisher completes" in pending
      "publisher errs out" in pending
    }
    "work initialized" when {
      "subscriber requests elements" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
      }
      "publisher sends element" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("test")
        downstream.expectNext("test")
      }
      "publisher sends elements and then completes" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.sendComplete()
        downstream.expectNext("test")
        downstream.expectComplete()
      }
      "publisher immediately completes" in pending
      "publisher immediately fails" in pending
      "operation publishes Producer" in new InitializedChainSetup[String, Producer[String]](Span(_ == "end")) {
        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1)

        upstreamSubscription.sendNext("a")
        val subStream = downstream.expectNext()
        val subStreamConsumer = TestKit.consumerProbe[String]()
        subStream.getPublisher.subscribe(subStreamConsumer.getSubscriber)
        val subStreamSubscription = subStreamConsumer.expectSubscription()

        subStreamSubscription.requestMore(1)
        subStreamConsumer.expectNext("a")

        subStreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("end")
        subStreamConsumer.expectNext("end")
        subStreamConsumer.expectComplete()

        upstreamSubscription.sendNext("test")
        val subStream2 = downstream.expectNext()
        val subStreamConsumer2 = TestKit.consumerProbe[String]()
        subStream2.getPublisher.subscribe(subStreamConsumer2.getSubscriber)
        val subStreamSubscription2 = subStreamConsumer2.expectSubscription()

        subStreamSubscription2.requestMore(1)
        subStreamConsumer2.expectNext("test")

        subStreamSubscription2.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("abc")
        subStreamConsumer2.expectNext("abc")

        subStreamSubscription2.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("end")
        upstreamSubscription.sendComplete()
        downstream.expectComplete()
        subStreamConsumer2.expectNext("end")
        subStreamConsumer2.expectComplete()
      }
      "operation consumes Producer" in new InitializedChainSetup[Producer[String], String](Flatten()) {
        downstreamSubscription.requestMore(4)
        upstream.expectRequestMore(upstreamSubscription, 1)

        val subStream = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream)
        val subStreamSubscription = subStream.expectSubscription()
        subStream.expectRequestMore(subStreamSubscription, 4)
        subStreamSubscription.sendNext("test")
        downstream.expectNext("test")
        subStreamSubscription.sendNext("abc")
        downstream.expectNext("abc")
        subStreamSubscription.sendComplete()

        upstream.expectRequestMore(upstreamSubscription, 1)

        val subStream2 = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream2)
        upstreamSubscription.sendComplete()
        val subStreamSubscription2 = subStream2.expectSubscription()
        subStream2.expectRequestMore(subStreamSubscription2, 2)
        subStreamSubscription2.sendNext("123")
        downstream.expectNext("123")

        subStreamSubscription2.sendComplete()
        downstream.expectComplete()
      }
      "complex operation" in pending
    }
    "work with multiple subscribers" when {
      "one subscribes while elements were requested before" in pending
    }
    "work in special situations" when {
      "single subscriber cancels subscription while receiving data" in pending
    }
    "work after initial upstream was completed" when {}
    "work when subscribed to multiple publishers" when {
      "really?" in pending
    }
  }

  override protected def afterAll(): Unit = system.shutdown()

  def processor[I, O](operation: Operation[I, O]): Processor[I, O] =
    OperationProcessor(operation, ProcessorSettings(system))

  class InitializedChainSetup[I, O](operation: Operation[I, O]) {
    val upstream = TestKit.producerProbe[I]()
    val downstream = TestKit.consumerProbe[O]()

    import DSL._

    val processed = upstream.andThen(operation).consume()
    val upstreamSubscription = upstream.expectSubscription()
    processed.link(downstream)
    val downstreamSubscription = downstream.expectSubscription()
  }
}

