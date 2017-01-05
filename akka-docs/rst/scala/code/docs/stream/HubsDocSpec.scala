/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream

import akka.NotUsed
import akka.stream.{ ActorMaterializer, KillSwitches, UniqueKillSwitch }
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import docs.CompileOnlySpec

import scala.concurrent.duration._

class HubsDocSpec extends AkkaSpec with CompileOnlySpec {
  implicit val materializer = ActorMaterializer()

  "Hubs" must {

    "demonstrate creating a dynamic merge" in {
      def println(s: String) = testActor ! s

      //#merge-hub
      // A simple consumer that will print to the console for now
      val consumer = Sink.foreach(println)

      // Attach a MergeHub Source to the consumer. This will materialize to a
      // corresponding Sink.
      val runnableGraph: RunnableGraph[Sink[String, NotUsed]] =
        MergeHub.source[String](perProducerBufferSize = 16).to(consumer)

      // By running/materializing the consumer we get back a Sink, and hence
      // now have access to feed elements into it. This Sink can be materialized
      // any number of times, and every element that enters the Sink will
      // be consumed by our consumer.
      val toConsumer: Sink[String, NotUsed] = runnableGraph.run()

      // Feeding two independent sources into the hub.
      Source.single("Hello!").runWith(toConsumer)
      Source.single("Hub!").runWith(toConsumer)
      //#merge-hub

      expectMsgAllOf("Hello!", "Hub!")
    }

    "demonstrate creating a dynamic broadcast" in compileOnlySpec {
      //#broadcast-hub
      // A simple producer that publishes a new "message" every second
      val producer = Source.tick(1.second, 1.second, "New message")

      // Attach a BroadcastHub Sink to the producer. This will materialize to a
      // corresponding Source.
      // (We need to use toMat and Keep.right since by default the materialized
      // value to the left is used)
      val runnableGraph: RunnableGraph[Source[String, NotUsed]] =
        producer.toMat(BroadcastHub.sink(bufferSize = 256))(Keep.right)

      // By running/materializing the producer, we get back a Source, which
      // gives us access to the elements published by the producer.
      val fromProducer: Source[String, NotUsed] = runnableGraph.run()

      // Print out messages from the producer in two independent consumers
      fromProducer.runForeach(msg => println("consumer1: " + msg))
      fromProducer.runForeach(msg => println("consumer2: " + msg))
      //#broadcast-hub
    }

    "demonstrate combination" in {
      def println(s: String) = testActor ! s

      //#pub-sub-1
      // Obtain a Sink and Source which will publish and receive from the "bus" respectively.
      val (sink, source) =
        MergeHub.source[String](perProducerBufferSize = 16)
          .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
          .run()
      //#pub-sub-1

      //#pub-sub-2
      // Ensure that the Broadcast output is dropped if there are no listening parties.
      // If this dropping Sink is not attached, then the broadcast hub will not drop any
      // elements itself when there are no subscribers, backpressuring the producer instead.
      source.runWith(Sink.ignore)
      //#pub-sub-2

      //#pub-sub-3
      // We create now a Flow that represents a publish-subscribe channel using the above
      // started stream as its "topic". We add two more features, external cancellation of
      // the registration and automatic cleanup for very slow subscribers.
      val busFlow: Flow[String, String, UniqueKillSwitch] =
        Flow.fromSinkAndSource(sink, source)
          .joinMat(KillSwitches.singleBidi[String, String])(Keep.right)
          .backpressureTimeout(3.seconds)
      //#pub-sub-3

      //#pub-sub-4
      val switch: UniqueKillSwitch =
        Source.repeat("Hello world!")
          .viaMat(busFlow)(Keep.right)
          .to(Sink.foreach(println))
          .run()

      // Shut down externally
      switch.shutdown()
      //#pub-sub-4
    }

  }

}
