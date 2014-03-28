/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.stream.{ GeneratorSettings, Stream, ProcessorGenerator }
import akka.actor.ActorSystem

class ChainSetup[I, O](stream: Stream[I] ⇒ Stream[O], val settings: GeneratorSettings)(implicit val system: ActorSystem) {
  val upstream = StreamTestKit.producerProbe[I]()
  val downstream = StreamTestKit.consumerProbe[O]()

  private val s = stream(Stream(upstream))
  val producer = s.toProducer(ProcessorGenerator(settings))
  val upstreamSubscription = upstream.expectSubscription()
  producer.produceTo(downstream)
  val downstreamSubscription = downstream.expectSubscription()
}
