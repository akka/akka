/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.stream.{ MaterializerSettings, FlowMaterializer }
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow

class ChainSetup[I, O](stream: Flow[I] ⇒ Flow[O], val settings: MaterializerSettings)(implicit val system: ActorSystem) {
  val upstream = StreamTestKit.PublisherProbe[I]()
  val downstream = StreamTestKit.SubscriberProbe[O]()

  private val s = stream(Flow(upstream))
  val publisher = s.toPublisher(FlowMaterializer(settings))
  val upstreamSubscription = upstream.expectSubscription()
  publisher.subscribe(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
