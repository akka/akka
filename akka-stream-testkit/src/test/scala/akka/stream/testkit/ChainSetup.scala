/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.NotUsed
import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import akka.stream.ActorMaterializer

class ChainSetup[In, Out, M](
    stream: Flow[In, In, NotUsed] => Flow[In, Out, M],
    val settings: ActorMaterializerSettings,
    materializer: ActorMaterializer,
    toPublisher: (Source[Out, _], ActorMaterializer) => Publisher[Out])(implicit val system: ActorSystem) {

  def this(
      stream: Flow[In, In, NotUsed] => Flow[In, Out, M],
      settings: ActorMaterializerSettings,
      toPublisher: (Source[Out, _], ActorMaterializer) => Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, ActorMaterializer(settings)(system), toPublisher)(system)

  def this(
      stream: Flow[In, In, NotUsed] => Flow[In, Out, M],
      settings: ActorMaterializerSettings,
      materializerCreator: (ActorMaterializerSettings, ActorRefFactory) => ActorMaterializer,
      toPublisher: (Source[Out, _], ActorMaterializer) => Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = TestPublisher.manualProbe[In]()
  val downstream = TestSubscriber.probe[Out]()
  private val s = Source.fromPublisher(upstream).via(stream(Flow[In].map(x => x).named("buh")))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
