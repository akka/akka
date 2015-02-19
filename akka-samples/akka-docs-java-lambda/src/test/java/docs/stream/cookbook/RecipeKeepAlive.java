/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.*;
import akka.stream.testkit.StreamTestKit;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Subscription;

public class RecipeKeepAlive extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeKeepAlive");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  class Tick {}
  public final Tick TICK = new Tick();

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        StreamTestKit.PublisherProbe<Tick> tickPub = new StreamTestKit.PublisherProbe<>(system);
        StreamTestKit.PublisherProbe<ByteString> dataPub = new StreamTestKit.PublisherProbe<>(system);
        StreamTestKit.SubscriberProbe<ByteString> sub = new StreamTestKit.SubscriberProbe<>(system);
        Source<Tick> ticks = Source.from(tickPub);
        Source<ByteString> data = Source.from(dataPub);
        Sink<ByteString> sink = Sink.create(sub);

        ByteString keepAliveMessage = ByteString.fromArray(new byte[]{11});

        //#inject-keepalive
        Source<ByteString> keepAliveStream =
          ticks.conflate(tick -> keepAliveMessage, (msg, newTick) -> msg);

        MergePreferred<ByteString> unfairMerge = MergePreferred.create();
        FlowGraph g = new FlowGraphBuilder()
          .addEdge(data, unfairMerge.preferred()) // If data is available then no keepalive is injected
          .addEdge(keepAliveStream, unfairMerge)
          .addEdge(unfairMerge, sink)
          .build();
        //#inject-keepalive

        g.run(mat);

        StreamTestKit.AutoPublisher<Tick> manualTicks = new StreamTestKit.AutoPublisher<>(tickPub, 0);
        StreamTestKit.AutoPublisher<ByteString> manualData = new StreamTestKit.AutoPublisher<>(dataPub, 0);

        Subscription subscription = sub.expectSubscription();

        manualTicks.sendNext(TICK);

        // pending data will overcome the keepalive
        manualData.sendNext(ByteString.fromArray(new byte[]{1}));
        manualData.sendNext(ByteString.fromArray(new byte[]{2}));
        manualData.sendNext(ByteString.fromArray(new byte[]{3}));

        subscription.request(1);
        sub.expectNext(ByteString.fromArray(new byte[]{1}));
        subscription.request(2);
        sub.expectNext(ByteString.fromArray(new byte[]{2}));
        sub.expectNext(ByteString.fromArray(new byte[]{3}));

        subscription.request(1);
        sub.expectNext(keepAliveMessage);

        subscription.request(1);
        manualTicks.sendNext(TICK);
        sub.expectNext(keepAliveMessage);

        manualData.sendComplete();
        manualTicks.sendComplete();

        sub.expectComplete();
      }
    };
  }

}

