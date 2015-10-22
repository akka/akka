/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.FanInShape2;
import akka.stream.FlowShape;
import akka.stream.Materializer;
import akka.stream.SourceShape;
import akka.stream.ClosedShape;
import akka.stream.javadsl.*;
import akka.stream.javadsl.ZipWith;
import akka.stream.testkit.*;
import akka.stream.testkit.javadsl.*;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

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

  final Materializer mat = ActorMaterializer.create(system);

  class Trigger {
  }

  public final Trigger TRIGGER = new Trigger();

  @Test
  public void zipped() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<Trigger, TestPublisher.Probe<Trigger>> triggerSource = TestSource.probe(system);
        final Sink<Message, TestSubscriber.Probe<Message>> messageSink = TestSink.probe(system);

        //#manually-triggered-stream
        final RunnableGraph<Pair<TestPublisher.Probe<Trigger>, TestSubscriber.Probe<Message>>> g =
          RunnableGraph.<Pair<TestPublisher.Probe<Trigger>, TestSubscriber.Probe<Message>>>fromGraph(
            FlowGraph.create(
              triggerSource,
              messageSink,
              (p, s) -> new Pair<>(p, s),
              (builder, source, sink) -> {
                SourceShape<Message> elements =
                  builder.add(Source.from(Arrays.asList("1", "2", "3", "4")).map(t -> new Message(t)));
                FlowShape<Pair<Message, Trigger>, Message> takeMessage =
                  builder.add(Flow.<Pair<Message, Trigger>>create().map(p -> p.first()));
                final FanInShape2<Message, Trigger, Pair<Message, Trigger>> zip =
                  builder.add(Zip.create());
                builder.from(elements).toInlet(zip.in0());
                builder.from(source).toInlet(zip.in1());
                builder.from(zip.out()).via(takeMessage).to(sink);
                return ClosedShape.getInstance();
              }
            )
          );
        //#manually-triggered-stream

        Pair<TestPublisher.Probe<Trigger>, TestSubscriber.Probe<Message>> pubSub = g.run(mat);
        TestPublisher.Probe<Trigger> pub = pubSub.first();
        TestSubscriber.Probe<Message> sub = pubSub.second();

        FiniteDuration timeout = FiniteDuration.create(100, TimeUnit.MILLISECONDS);
        sub.expectSubscription().request(1000);
        sub.expectNoMsg(timeout);

        pub.sendNext(TRIGGER);
        sub.expectNext(new Message("1"));
        sub.expectNoMsg(timeout);

        pub.sendNext(TRIGGER);
        pub.sendNext(TRIGGER);
        sub.expectNext(new Message("2"));
        sub.expectNext(new Message("3"));
        sub.expectNoMsg(timeout);

        pub.sendNext(TRIGGER);
        sub.expectNext(new Message("4"));
        sub.expectComplete();
      }
    };
  }

  @Test
  public void zipWith() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<Trigger, TestPublisher.Probe<Trigger>> triggerSource = TestSource.probe(system);
        final Sink<Message, TestSubscriber.Probe<Message>> messageSink = TestSink.probe(system);

        //#manually-triggered-stream-zipwith
        final RunnableGraph<Pair<TestPublisher.Probe<Trigger>, TestSubscriber.Probe<Message>>> g =
          RunnableGraph.<Pair<TestPublisher.Probe<Trigger>, TestSubscriber.Probe<Message>>>fromGraph(
            FlowGraph.create(
              triggerSource,
              messageSink,
              (p, s) -> new Pair<>(p, s),
              (builder, source, sink) -> {
                final SourceShape<Message> elements =
                  builder.add(Source.from(Arrays.asList("1", "2", "3", "4")).map(t -> new Message(t)));
                final FanInShape2<Message, Trigger, Message> zipWith =
                  builder.add(ZipWith.create((msg, trigger) -> msg));
                builder.from(elements).toInlet(zipWith.in0());
                builder.from(source).toInlet(zipWith.in1());
                builder.from(zipWith.out()).to(sink);
                return ClosedShape.getInstance();
              }
            )
          );
        //#manually-triggered-stream-zipwith

        Pair<TestPublisher.Probe<Trigger>, TestSubscriber.Probe<Message>> pubSub = g.run(mat);
        TestPublisher.Probe<Trigger> pub = pubSub.first();
        TestSubscriber.Probe<Message> sub = pubSub.second();

        FiniteDuration timeout = FiniteDuration.create(100, TimeUnit.MILLISECONDS);
        sub.expectSubscription().request(1000);
        sub.expectNoMsg(timeout);

        pub.sendNext(TRIGGER);
        sub.expectNext(new Message("1"));
        sub.expectNoMsg(timeout);

        pub.sendNext(TRIGGER);
        pub.sendNext(TRIGGER);
        sub.expectNext(new Message("2"));
        sub.expectNext(new Message("3"));
        sub.expectNoMsg(timeout);

        pub.sendNext(TRIGGER);
        sub.expectNext(new Message("4"));
        sub.expectComplete();

      }
    };
  }

}
