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
        upstream.probe.expectMsg(RequestMore(upstreamSubscription, 1))
      }
      "publisher sends element" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.probe.expectMsg(RequestMore(upstreamSubscription, 1))
        upstreamSubscription.sendNext("test")
        downstream.probe.expectMsg(OnNext("test"))
      }
      "publisher sends elements and then completes" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.probe.expectMsg(RequestMore(upstreamSubscription, 1))
        upstreamSubscription.sendNext("test")
        upstreamSubscription.sendComplete()
        downstream.probe.expectMsg(OnNext("test"))
        downstream.probe.expectMsg(OnComplete)
      }
      "publisher immediately completes" in pending
      "publisher immediately fails" in pending
      "operation publishes Producer" in pending
      "operation consumes Producer" in new InitializedChainSetup[Producer[String], String](Flatten()) {
        downstreamSubscription.requestMore(4)
        upstream.probe.expectMsg(RequestMore(upstreamSubscription, 1))

        val subStream = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream)
        val subStreamSubscription = subStream.expectSubscription()
        subStream.probe.expectMsg(RequestMore(subStreamSubscription, 4))
        subStreamSubscription.sendNext("test")
        downstream.expectEvent(OnNext("test"))
        subStreamSubscription.sendNext("abc")
        downstream.expectEvent(OnNext("abc"))
        subStreamSubscription.sendComplete()

        upstream.probe.expectMsg(RequestMore(upstreamSubscription, 1))

        val subStream2 = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream2)
        upstreamSubscription.sendComplete()
        val subStreamSubscription2 = subStream2.expectSubscription()
        subStream2.probe.expectMsg(RequestMore(subStreamSubscription2, 2))
        subStreamSubscription2.sendNext("123")
        downstream.expectEvent(OnNext("123"))

        subStreamSubscription2.sendComplete()
        downstream.expectEvent(OnComplete)
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

