package akka.stream.testkit

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.MaterializerSettings
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import akka.stream.FlowMaterializer

class ChainSetup[In, Out](
  stream: Flow[In, In, _] ⇒ Flow[In, Out, _],
  val settings: MaterializerSettings,
  materializer: FlowMaterializer,
  toPublisher: (Source[Out, _], FlowMaterializer) ⇒ Publisher[Out])(implicit val system: ActorSystem) {

  def this(stream: Flow[In, In, _] ⇒ Flow[In, Out, _], settings: MaterializerSettings, toPublisher: (Source[Out, _], FlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, FlowMaterializer(settings)(system), toPublisher)(system)

  def this(stream: Flow[In, In, _] ⇒ Flow[In, Out, _],
           settings: MaterializerSettings,
           materializerCreator: (MaterializerSettings, ActorRefFactory) ⇒ FlowMaterializer,
           toPublisher: (Source[Out, _], FlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = StreamTestKit.PublisherProbe[In]()
  val downstream = StreamTestKit.SubscriberProbe[Out]()
  private val s = Source(upstream).via(stream(Flow[In].map(x ⇒ x).withAttributes(OperationAttributes.name("buh"))))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
