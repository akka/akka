/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.*;
import akka.stream.testkit.StreamTestKit;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class RecipeManualTrigger extends RecipeTest {
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

  class Trigger {}
  public final Trigger TRIGGER = new Trigger();

  @Test
  public void zipped() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        Source<Message> elements = Source.from(Arrays.asList("1", "2", "3", "4")).map(t -> new Message(t));

        StreamTestKit.PublisherProbe<Trigger> pub = new StreamTestKit.PublisherProbe<>(system);
        StreamTestKit.SubscriberProbe<Message> sub = new StreamTestKit.SubscriberProbe<>(system);
        Source<Trigger> triggerSource = Source.from(pub);
        Sink<Message> sink = Sink.create(sub);

        //#manually-triggered-stream
        Zip2With<Message, Trigger, Pair<Message, Trigger>> zip = Zip.create();
        Flow<Pair<Message, Trigger>, Message> takeMessage =
          Flow.<Pair<Message, Trigger>>create().map(p -> p.first());

        FlowGraph g = new FlowGraphBuilder()
          .addEdge(elements, zip.left())
          .addEdge(triggerSource, zip.right())
          .addEdge(zip.out(), takeMessage, sink)
          .build();
        //#manually-triggered-stream

        g.run(mat);

        StreamTestKit.AutoPublisher<Trigger> manualTicks = new StreamTestKit.AutoPublisher<>(pub, 0);
        FiniteDuration timeout = FiniteDuration.create(100, TimeUnit.MILLISECONDS);
        sub.expectSubscription().request(1000);
        sub.expectNoMsg(timeout);

        manualTicks.sendNext(TRIGGER);
        sub.expectNext(new Message("1"));
        sub.expectNoMsg(timeout);

        manualTicks.sendNext(TRIGGER);
        manualTicks.sendNext(TRIGGER);
        sub.expectNext(new Message("2"));
        sub.expectNext(new Message("3"));
        sub.expectNoMsg(timeout);

        manualTicks.sendNext(TRIGGER);
        sub.expectNext(new Message("4"));
        sub.expectComplete();
      }
    };
  }

  @Test
  public void zipWith() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        Source<Message> elements = Source.from(Arrays.asList("1", "2", "3", "4")).map(t -> new Message(t));

        StreamTestKit.PublisherProbe<Trigger> pub = new StreamTestKit.PublisherProbe<>(system);
        StreamTestKit.SubscriberProbe<Message> sub = new StreamTestKit.SubscriberProbe<>(system);
        Source<Trigger> triggerSource = Source.from(pub);
        Sink<Message> sink = Sink.create(sub);

        //#manually-triggered-stream-zipwith
        Zip2With<Message, Trigger, Message> zipWith =
          ZipWith.create((msg, trigger) -> msg);

        FlowGraph g = new FlowGraphBuilder()
          .addEdge(elements, zipWith.left())
          .addEdge(triggerSource, zipWith.right())
          .addEdge(zipWith.out(), sink)
          .build();
        //#manually-triggered-stream-zipwith

        g.run(mat);

        StreamTestKit.AutoPublisher<Trigger> manualTicks = new StreamTestKit.AutoPublisher<>(pub, 0);
        FiniteDuration timeout = FiniteDuration.create(100, TimeUnit.MILLISECONDS);
        sub.expectSubscription().request(1000);
        sub.expectNoMsg(timeout);

        manualTicks.sendNext(TRIGGER);
        sub.expectNext(new Message("1"));
        sub.expectNoMsg(timeout);

        manualTicks.sendNext(TRIGGER);
        manualTicks.sendNext(TRIGGER);
        sub.expectNext(new Message("2"));
        sub.expectNext(new Message("3"));
        sub.expectNoMsg(timeout);

        manualTicks.sendNext(TRIGGER);
        sub.expectNext(new Message("4"));
        sub.expectComplete();
      }
    };
  }


}

