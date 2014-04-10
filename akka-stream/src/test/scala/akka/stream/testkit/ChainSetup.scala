/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.stream.{ MaterializerSettings, FlowMaterializer }
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow

class ChainSetup[I, O](stream: Flow[I] ⇒ Flow[O], val settings: MaterializerSettings)(implicit val system: ActorSystem) {
  val upstream = StreamTestKit.producerProbe[I]()
  val downstream = StreamTestKit.consumerProbe[O]()

  private val s = stream(Flow(upstream))
  val producer = s.toProducer(FlowMaterializer(settings))
  val upstreamSubscription = upstream.expectSubscription()
  producer.produceTo(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
