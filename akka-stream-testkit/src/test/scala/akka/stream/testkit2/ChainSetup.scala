package akka.stream.testkit2

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.MaterializerSettings
import akka.stream.scaladsl2._
import akka.stream.testkit.StreamTestKit
import org.reactivestreams.Publisher

import scala.annotation.unchecked.uncheckedVariance

class ChainSetup[In, Out](
  stream: ProcessorFlow[In, In] ⇒ ProcessorFlow[In, Out],
  val settings: MaterializerSettings,
  materializer: FlowMaterializer,
  toPublisher: (FlowWithSource[In, Out], FlowMaterializer) ⇒ Publisher[Out])(implicit val system: ActorSystem) {

  def this(stream: ProcessorFlow[In, In] ⇒ ProcessorFlow[In, Out], settings: MaterializerSettings, toPublisher: (FlowWithSource[In, Out], FlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, FlowMaterializer(settings)(system), toPublisher)(system)

  def this(stream: ProcessorFlow[In, In] ⇒ ProcessorFlow[In, Out], settings: MaterializerSettings, materializerCreator: (MaterializerSettings, ActorRefFactory) ⇒ FlowMaterializer, toPublisher: (FlowWithSource[In, Out], FlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = StreamTestKit.PublisherProbe[In]()
  val downstream = StreamTestKit.SubscriberProbe[Out]()
  private val s = stream(FlowFrom[In]).withSource(PublisherSource(upstream))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
