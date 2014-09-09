package akka.stream.testkit2

import akka.actor.ActorSystem
import akka.stream.MaterializerSettings
import akka.stream.scaladsl2.{ FlowFrom, FlowMaterializer, ProcessorFlow, PublisherSource }
import akka.stream.testkit.StreamTestKit

class ChainSetup[In, Out](stream: ProcessorFlow[In, In] ⇒ ProcessorFlow[In, Out], val settings: MaterializerSettings)(implicit val system: ActorSystem) {
  val upstream = StreamTestKit.PublisherProbe[In]()
  val downstream = StreamTestKit.SubscriberProbe[Out]()

  private val s = stream(FlowFrom[In]).withSource(PublisherSource(upstream))
  val publisher = s.toPublisher()(FlowMaterializer(settings))
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
