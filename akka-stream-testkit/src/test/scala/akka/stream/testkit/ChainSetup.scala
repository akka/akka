/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.NotUsed
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.Materializer
import akka.stream.scaladsl._
import com.github.ghik.silencer.silent
import org.reactivestreams.Publisher

class ChainSetup[In, Out, M](
    stream: Flow[In, In, NotUsed] => Flow[In, Out, M],
    val settings: ActorMaterializerSettings,
    materializer: Materializer,
    toPublisher: (Source[Out, _], Materializer) => Publisher[Out])(implicit val system: ActorSystem) {

  @silent("deprecated")
  def this(
      stream: Flow[In, In, NotUsed] => Flow[In, Out, M],
      settings: ActorMaterializerSettings,
      toPublisher: (Source[Out, _], Materializer) => Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, ActorMaterializer(settings)(system), toPublisher)(system)

  @silent("deprecated")
  def this(
      stream: Flow[In, In, NotUsed] => Flow[In, Out, M],
      settings: ActorMaterializerSettings,
      materializerCreator: (ActorMaterializerSettings, ActorRefFactory) => Materializer,
      toPublisher: (Source[Out, _], Materializer) => Publisher[Out])(implicit system: ActorSystem) =
    this(stream, settings, materializerCreator(settings, system), toPublisher)(system)

  val upstream = TestPublisher.manualProbe[In]()
  val downstream = TestSubscriber.probe[Out]()
  private val s = Source.fromPublisher(upstream).via(stream(Flow[In].map(x => x).named("buh")))
  val publisher = toPublisher(s, materializer)
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
