/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.stream.{ GeneratorSettings, ProcessorGenerator }
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow

class ChainSetup[I, O](stream: Flow[I] ⇒ Flow[O], val settings: GeneratorSettings)(implicit val system: ActorSystem) {
  val upstream = StreamTestKit.producerProbe[I]()
  val downstream = StreamTestKit.consumerProbe[O]()

  private val s = stream(Flow(upstream))
  val producer = s.toProducer(ProcessorGenerator(settings))
  val upstreamSubscription = upstream.expectSubscription()
  producer.produceTo(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
