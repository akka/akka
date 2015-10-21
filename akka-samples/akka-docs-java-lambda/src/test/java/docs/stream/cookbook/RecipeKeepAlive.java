/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.FlowShape;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import akka.stream.scaladsl.MergePreferred.MergePreferredShape;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import scala.Tuple3;
import scala.runtime.BoxedUnit;

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
        final Source<Tick, TestPublisher.Probe<Tick>> ticks = TestSource.probe(system);
        final Source<ByteString, TestPublisher.Probe<ByteString>> data = TestSource.probe(system);
        final Sink<ByteString, TestSubscriber.Probe<ByteString>> sink = TestSink.probe(system);

        ByteString keepAliveMessage = ByteString.fromArray(new byte[] { 11 });

        //@formatter:off
        //#inject-keepalive
        final Tuple3<
            TestPublisher.Probe<Tick>,
            TestPublisher.Probe<ByteString>,
            TestSubscriber.Probe<ByteString>
          > ticksDataRes =
          FlowGraph.factory().closed3(ticks, data, sink,
            (t, d, s) -> new Tuple3(t, d, s),
            (builder, t, d, s) -> {
              final int secondaryPorts = 1;
              final MergePreferredShape<ByteString> unfairMerge =
                builder.graph(MergePreferred.create(secondaryPorts));
              FlowShape<Tick, ByteString> tickToKeepAlivePacket =
                      builder.graph(Flow.of(Tick.class).conflate(tick -> keepAliveMessage, (msg, newTick) -> msg));

              // If data is available then no keepalive is injected
              builder.from(d).toInlet(unfairMerge.preferred());
              builder.from(t).via(tickToKeepAlivePacket).toInlet(unfairMerge.in(0));
              builder.from(unfairMerge.out()).to(s);
            }
          ).run(mat);
        //#inject-keepalive
        //@formatter:on

        final TestPublisher.Probe<Tick> manualTicks = ticksDataRes._1();
        final TestPublisher.Probe<ByteString> manualData = ticksDataRes._2();
        final TestSubscriber.Probe<ByteString> sub = ticksDataRes._3();

        manualTicks.sendNext(TICK);

        // pending data will overcome the keepalive
        manualData.sendNext(ByteString.fromArray(new byte[] { 1 }));
        manualData.sendNext(ByteString.fromArray(new byte[] { 2 }));
        manualData.sendNext(ByteString.fromArray(new byte[] { 3 }));

        sub.requestNext(ByteString.fromArray(new byte[] { 1 }));
        sub.request(2);
        sub.expectNext(ByteString.fromArray(new byte[] { 2 }));
        sub.expectNext(ByteString.fromArray(new byte[] { 3 }));

        sub.requestNext(keepAliveMessage);

        sub.request(1);
        manualTicks.sendNext(TICK);
        sub.expectNext(keepAliveMessage);

        manualData.sendComplete();
        manualTicks.sendComplete();

        sub.expectComplete();
      }
    };
  }

}
