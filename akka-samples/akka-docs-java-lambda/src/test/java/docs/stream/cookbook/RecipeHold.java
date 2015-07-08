/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.DetachedContext;
import akka.stream.stage.DetachedStage;
import akka.stream.stage.DownstreamDirective;
import akka.stream.stage.UpstreamDirective;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class RecipeHold extends RecipeTest {
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

  final Materializer mat = ActorMaterializer.create(system);

  //#hold-version-1
  class HoldWithInitial<T> extends DetachedStage<T, T> {
    private T currentValue;

    public HoldWithInitial(T initial) {
      currentValue = initial;
    }

    @Override
    public UpstreamDirective onPush(T elem, DetachedContext<T> ctx) {
      currentValue = elem;
      return ctx.pull();
    }

    @Override
    public DownstreamDirective onPull(DetachedContext<T> ctx) {
      return ctx.push(currentValue);
    }
  }
  //#hold-version-1

  //#hold-version-2
  class HoldWithWait<T> extends DetachedStage<T, T> {
    private T currentValue = null;
    private boolean waitingFirstValue = true;

    @Override
    public UpstreamDirective onPush(T elem, DetachedContext<T> ctx) {
      currentValue = elem;
      waitingFirstValue = false;
      if (ctx.isHoldingDownstream()) {
        return ctx.pushAndPull(currentValue);
      } else {
        return ctx.pull();
      }
    }

    @Override
    public DownstreamDirective onPull(DetachedContext<T> ctx) {
      if (waitingFirstValue) {
        return ctx.holdDownstream();
      } else {
        return ctx.push(currentValue);
      }
    }
  }
  //#hold-version-2

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<Integer, TestPublisher.Probe<Integer>> source = TestSource.probe(system);
        final Sink<Integer, TestSubscriber.Probe<Integer>> sink = TestSink.probe(system);

        Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Integer>> pubSub =
          source.transform(() -> new HoldWithInitial<>(0)).toMat(sink, Keep.both()).run(mat);
        TestPublisher.Probe<Integer> pub = pubSub.first();
        TestSubscriber.Probe<Integer> sub = pubSub.second();

        sub.requestNext(0);
        sub.requestNext(0);

        pub.sendNext(1);
        pub.sendNext(2);

        sub.request(2);
        sub.expectNext(2, 2);

        pub.sendComplete();
        sub.request(1);
        sub.expectComplete();
      }
    };
  }

  @Test
  public void workForVersion2() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<Integer, TestPublisher.Probe<Integer>> source = TestSource.probe(system);
        final Sink<Integer, TestSubscriber.Probe<Integer>> sink = TestSink.probe(system);

        Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Integer>> pubSub =
          source.transform(() -> new HoldWithWait<>()).toMat(sink, Keep.both()).run(mat);
        TestPublisher.Probe<Integer> pub = pubSub.first();
        TestSubscriber.Probe<Integer> sub = pubSub.second();

        FiniteDuration timeout = FiniteDuration.create(200, TimeUnit.MILLISECONDS);

        sub.request(1);
        sub.expectNoMsg(timeout);

        pub.sendNext(1);
        sub.expectNext(1);

        pub.sendNext(2);
        pub.sendNext(3);

        sub.request(2);
        sub.expectNext(3, 3);

        pub.sendComplete();
        sub.request(1);
        sub.expectComplete();
      }
    };
  }

}
