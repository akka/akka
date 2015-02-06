/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.StreamTestKit;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Subscription;
import scala.concurrent.duration.FiniteDuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class RecipeMissedTicks extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeMultiGroupBy");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);


  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      class Tick {
      }

      final Tick Tick = new Tick();

      {
        StreamTestKit.PublisherProbe<Tick> pub = new StreamTestKit.PublisherProbe<Tick>(system);
        StreamTestKit.SubscriberProbe<Integer> sub = new StreamTestKit.SubscriberProbe<Integer>(system);
        Source<Tick> tickSource = Source.from(pub);
        Sink<Integer> sink = Sink.create(sub);

        //#missed-ticks
        // tickStream is a Source<Tick>
        Source<Integer> missedTicks =
          tickSource.conflate(tick -> 0, (missed, tick) -> missed + 1);
        //#missed-ticks

        missedTicks.to(sink).run(mat);
        StreamTestKit.AutoPublisher<Tick> manualSource = new StreamTestKit.AutoPublisher<Tick>(pub, 0);

        manualSource.sendNext(Tick);
        manualSource.sendNext(Tick);
        manualSource.sendNext(Tick);
        manualSource.sendNext(Tick);

        FiniteDuration timeout = FiniteDuration.create(200, TimeUnit.MILLISECONDS);

        Subscription subscription = sub.expectSubscription();
        subscription.request(1);
        sub.expectNext(3);

        subscription.request(1);
        sub.expectNoMsg(timeout);

        manualSource.sendNext(Tick);
        sub.expectNext(0);

        manualSource.sendComplete();
        subscription.request(1);
        sub.expectComplete();

      }
    };
  }

}

