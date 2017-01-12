/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.KillSwitches;
import akka.stream.Materializer;
import akka.stream.UniqueKillSwitch;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class HubDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer materializer;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("GraphDSLDocTest");
    materializer = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    materializer = null;
  }

  @Test
  public void dynamicMerge() {
    //#merge-hub
    // A simple consumer that will print to the console for now
    Sink<String, CompletionStage<Done>> consumer = Sink.foreach(System.out::println);

    // Attach a MergeHub Source to the consumer. This will materialize to a
    // corresponding Sink.
    RunnableGraph<Sink<String, NotUsed>> runnableGraph =
      MergeHub.of(String.class, 16).to(consumer);

    // By running/materializing the consumer we get back a Sink, and hence
    // now have access to feed elements into it. This Sink can be materialized
    // any number of times, and every element that enters the Sink will
    // be consumed by our consumer.
    Sink<String, NotUsed> toConsumer = runnableGraph.run(materializer);

    Source.single("Hello!").runWith(toConsumer, materializer);
    Source.single("Hub!").runWith(toConsumer, materializer);
    //#merge-hub
  }

  @Test
  public void dynamicBroadcast() {
    // Used to be able to clean up the running stream
    ActorMaterializer materializer = ActorMaterializer.create(system);

    //#broadcast-hub
    // A simple producer that publishes a new "message" every second
    Source<String, Cancellable> producer = Source.tick(
      FiniteDuration.create(1, TimeUnit.SECONDS),
      FiniteDuration.create(1, TimeUnit.SECONDS),
      "New message"
    );

    // Attach a BroadcastHub Sink to the producer. This will materialize to a
    // corresponding Source.
    // (We need to use toMat and Keep.right since by default the materialized
    // value to the left is used)
    RunnableGraph<Source<String, NotUsed>> runnableGraph =
      producer.toMat(BroadcastHub.of(String.class, 256), Keep.right());

    // By running/materializing the producer, we get back a Source, which
    // gives us access to the elements published by the producer.
    Source<String, NotUsed> fromProducer = runnableGraph.run(materializer);

    // Print out messages from the producer in two independent consumers
    fromProducer.runForeach(msg -> System.out.println("consumer1: " + msg), materializer);
    fromProducer.runForeach(msg -> System.out.println("consumer2: " + msg), materializer);
    //#broadcast-hub

    // Cleanup
    materializer.shutdown();
  }

  @Test
  public void mergeBroadcastCombination() {
    //#pub-sub-1
    // Obtain a Sink and Source which will publish and receive from the "bus" respectively.
    Pair<Sink<String, NotUsed>, Source<String, NotUsed>> sinkAndSource =
      MergeHub.of(String.class, 16)
        .toMat(BroadcastHub.of(String.class, 256), Keep.both())
        .run(materializer);

    Sink<String, NotUsed> sink = sinkAndSource.first();
    Source<String, NotUsed> source = sinkAndSource.second();
    //#pub-sub-1

    //#pub-sub-2
    // Ensure that the Broadcast output is dropped if there are no listening parties.
    // If this dropping Sink is not attached, then the broadcast hub will not drop any
    // elements itself when there are no subscribers, backpressuring the producer instead.
    source.runWith(Sink.ignore(), materializer);
    //#pub-sub-2

    //#pub-sub-3
    // We create now a Flow that represents a publish-subscribe channel using the above
    // started stream as its "topic". We add two more features, external cancellation of
    // the registration and automatic cleanup for very slow subscribers.
    Flow<String, String, UniqueKillSwitch> busFlow =
      Flow.fromSinkAndSource(sink, source)
        .joinMat(KillSwitches.singleBidi(), Keep.right())
        .backpressureTimeout(FiniteDuration.create(1, TimeUnit.SECONDS));
    //#pub-sub-3

    //#pub-sub-4
    UniqueKillSwitch killSwitch =
      Source.repeat("Hello World!")
        .viaMat(busFlow, Keep.right())
        .to(Sink.foreach(System.out::println))
        .run(materializer);

    // Shut down externally
    killSwitch.shutdown();
    //#pub-sub-4
  }
}
