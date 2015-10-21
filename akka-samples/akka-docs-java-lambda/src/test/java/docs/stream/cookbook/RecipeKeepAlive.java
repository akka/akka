/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.FlowShape;
import akka.stream.Materializer;
import akka.stream.ClosedShape;
import akka.stream.javadsl.*;
import akka.stream.javadsl.RunnableGraph;
import akka.stream.scaladsl.MergePreferred.MergePreferredShape;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import scala.runtime.BoxedUnit;

import org.reactivestreams.Subscription;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

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

  final Materializer mat = ActorMaterializer.create(system);

  class Tick {}
  public final Tick TICK = new Tick();

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      {
        final TestPublisher.Probe<Tick> tickPub = TestPublisher.<Tick>probe(0, system);
        final TestPublisher.Probe<ByteString> dataPub = TestPublisher.<ByteString>probe(0, system);
        final TestSubscriber.ManualProbe<ByteString> sub = TestSubscriber.<ByteString>manualProbe(system);
        final Source<Tick, BoxedUnit> ticks = Source.from(tickPub);

        final Source<ByteString, BoxedUnit> dataStream = Source.from(dataPub);
        final ByteString keepAliveMessage = ByteString.fromArray(new byte[]{11});
        final Sink<ByteString, BoxedUnit> sink = Sink.create(sub);

        //@formatter:off
        //#inject-keepalive
        Flow<Tick, ByteString, BoxedUnit> tickToKeepAlivePacket =
          Flow.of(Tick.class).conflate(tick -> keepAliveMessage, (msg, newTick) -> msg);

        final RunnableGraph<BoxedUnit> graph = RunnableGraph.fromGraph(
          FlowGraph.create((builder) -> {
            final int secondaryPorts = 1;
              final MergePreferredShape<ByteString> unfairMerge =
                builder.add(MergePreferred.create(secondaryPorts));
              // If data is available then no keepalive is injected
              builder.from(builder.add(dataStream)).toInlet(unfairMerge.preferred());
              builder.from(builder.add(ticks))
                     .via(builder.add(tickToKeepAlivePacket))
                     .toInlet(unfairMerge.in(0));
              builder.from(unfairMerge.out()).to(builder.add(sink));
              return ClosedShape.getInstance();
            }
          )
        );
        //#inject-keepalive
        //@formatter:on
        graph.run(mat);
        final Subscription subscription = sub.expectSubscription();
        tickPub.sendNext(TICK);

        // pending data will overcome the keepalive
        dataPub.sendNext(ByteString.fromArray(new byte[]{1}));
        dataPub.sendNext(ByteString.fromArray(new byte[]{2}));
        dataPub.sendNext(ByteString.fromArray(new byte[]{3}));

        subscription.request(1);
        sub.expectNext(ByteString.fromArray(new byte[]{1}));
        subscription.request(2);
        sub.expectNext(ByteString.fromArray(new byte[]{2}));
        sub.expectNext(keepAliveMessage);
        subscription.request(1);
        sub.expectNext(ByteString.fromArray(new byte[]{3}));

        subscription.request(1);
        tickPub.sendNext(TICK);
        sub.expectNext(keepAliveMessage);

        dataPub.sendComplete();
        tickPub.sendComplete();

        sub.expectComplete();
      }
    };
  }

}
