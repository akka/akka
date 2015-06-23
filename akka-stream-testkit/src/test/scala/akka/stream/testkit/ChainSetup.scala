package akka.stream.testkit

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import akka.stream.ActorFlowMaterializer
import akka.stream.Attributes

class ChainSetup[In, Out](
  stream: Flow[In, In, _] ⇒ Flow[In, Out, _],
  val settings: ActorFlowMaterializerSettings,
  materializer: ActorFlowMaterializer,
  toPublisher: (Source[Out, _], ActorFlowMaterializer) ⇒ Publisher[Out])(implicit val system: ActorSystem) {

  def this(stream: Flow[In, In, _] ⇒ Flow[In, Out, _], settings: ActorFlowMaterializerSettings, toPublisher: (Source[Out, _], ActorFlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, ActorFlowMaterializer(settings)(system), toPublisher)(system)

  def this(stream: Flow[In, In, _] ⇒ Flow[In, Out, _],
           settings: ActorFlowMaterializerSettings,
           materializerCreator: (ActorFlowMaterializerSettings, ActorRefFactory) ⇒ ActorFlowMaterializer,
           toPublisher: (Source[Out, _], ActorFlowMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = TestPublisher.manualProbe[In]()
  val downstream = TestSubscriber.probe[Out]()
  private val s = Source(upstream).via(stream(Flow[In].map(x ⇒ x).named("buh")))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
