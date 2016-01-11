package akka.stream.testkit

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import akka.stream.ActorFlowMaterializer

class ChainSetup[In, Out](
  stream: Flow[In, In] ⇒ Flow[In, Out],
  val settings: ActorFlowMaterializerSettings,
  materializer: ActorFlowMaterializer,
  toPublisher: (Source[Out], ActorFlowMaterializer) ⇒ Publisher[Out])(implicit val system: ActorSystem) {

  def this(stream: Flow[In, In] ⇒ Flow[In, Out], settings: ActorFlowMaterializerSettings, toPublisher: (Source[Out], ActorFlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, ActorFlowMaterializer(settings)(system), toPublisher)(system)

  def this(stream: Flow[In, In] ⇒ Flow[In, Out], settings: ActorFlowMaterializerSettings, materializerCreator: (ActorFlowMaterializerSettings, ActorRefFactory) ⇒ ActorFlowMaterializer, toPublisher: (Source[Out], ActorFlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = StreamTestKit.PublisherProbe[In]()
  val downstream = StreamTestKit.SubscriberProbe[Out]()
  private val s = Source(upstream).via(stream(Flow[In]))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
