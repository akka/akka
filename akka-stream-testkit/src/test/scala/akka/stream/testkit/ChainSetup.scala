package akka.stream.testkit

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import akka.stream.ActorMaterializer
import akka.stream.Attributes

class ChainSetup[In, Out](
  stream: Flow[In, In, _] ⇒ Flow[In, Out, _],
  val settings: ActorMaterializerSettings,
  materializer: ActorMaterializer,
  toPublisher: (Source[Out, _], ActorMaterializer) ⇒ Publisher[Out])(implicit val system: ActorSystem) {

  def this(stream: Flow[In, In, _] ⇒ Flow[In, Out, _], settings: ActorMaterializerSettings, toPublisher: (Source[Out, _], ActorMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, ActorMaterializer(settings)(system), toPublisher)(system)

  def this(stream: Flow[In, In, _] ⇒ Flow[In, Out, _],
           settings: ActorMaterializerSettings,
           materializerCreator: (ActorMaterializerSettings, ActorRefFactory) ⇒ ActorMaterializer,
           toPublisher: (Source[Out, _], ActorMaterializer) ⇒ Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = TestPublisher.manualProbe[In]()
  val downstream = TestSubscriber.probe[Out]()
  private val s = Source(upstream).via(stream(Flow[In].map(x ⇒ x).named("buh")))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
