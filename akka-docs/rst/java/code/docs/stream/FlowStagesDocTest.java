/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.stage.*;
import akka.stream.testkit.*;
import akka.stream.testkit.javadsl.*;
import akka.testkit.JavaTestKit;

public class FlowStagesDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowStagesDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  static //#one-to-one
  public class Map<A, B> extends PushPullStage<A, B> {
    private final Function<A, B> f;
    public Map(Function<A, B> f) {
      this.f = f;
    }

    @Override public SyncDirective onPush(A elem, Context<B> ctx) {
      return ctx.push(f.apply(elem));
    }

    @Override public SyncDirective onPull(Context<B> ctx) {
      return ctx.pull();
    }
  }
  //#one-to-one

  static //#many-to-one
  public class Filter<A> extends PushPullStage<A, A> {
    private final Predicate<A> p;
    public Filter(Predicate<A> p) {
      this.p = p;
    }

    @Override public SyncDirective onPush(A elem, Context<A> ctx) {
      if (p.test(elem)) return ctx.push(elem);
      else return ctx.pull();
    }

    @Override public SyncDirective onPull(Context<A> ctx) {
      return ctx.pull();
    }
  }
  //#many-to-one

  //#one-to-many
  class Duplicator<A> extends PushPullStage<A, A> {
    private A lastElem = null;
    private boolean oneLeft = false;

    @Override public SyncDirective onPush(A elem, Context<A> ctx) {
      lastElem = elem;
      oneLeft = true;
      return ctx.push(elem);
    }

    @Override public SyncDirective onPull(Context<A> ctx) {
      if (!ctx.isFinishing()) {
        // the main pulling logic is below as it is demonstrated on the illustration
        if (oneLeft) {
          oneLeft = false;
          return ctx.push(lastElem);
        } else
          return ctx.pull();
      } else {
        // If we need to emit a final element after the upstream
        // finished
        if (oneLeft) return ctx.pushAndFinish(lastElem);
        else return ctx.finish();
      }
    }

    @Override public TerminationDirective onUpstreamFinish(Context<A> ctx) {
      return ctx.absorbTermination();
    }

  }
  //#one-to-many

  static//#pushstage
  public class Map2<A, B> extends PushStage<A, B> {
    private final Function<A, B> f;
    public Map2(Function<A, B> f) {
      this.f = f;
    }

    @Override public SyncDirective onPush(A elem, Context<B> ctx) {
      return ctx.push(f.apply(elem));
    }
  }

  public class Filter2<A> extends PushStage<A, A> {
    private final Predicate<A> p;
    public Filter2(Predicate<A> p) {
      this.p = p;
    }

    @Override public SyncDirective onPush(A elem, Context<A> ctx) {
      if (p.test(elem)) return ctx.push(elem);
      else return ctx.pull();
    }
  }
  //#pushstage

  static //#doubler-stateful
  public class Duplicator2<A> extends StatefulStage<A, A> {
    @Override public StageState<A, A> initial() {
      return new StageState<A, A>() {
        @Override public SyncDirective onPush(A elem, Context<A> ctx) {
          return emit(Arrays.asList(elem, elem).iterator(), ctx);
        }
      };
    }
  }
  //#doubler-stateful

  @Test
  public void demonstrateVariousPushPullStages() throws Exception {
    final Sink<Integer, CompletionStage<List<Integer>>> sink =
        Flow.of(Integer.class).grouped(10).toMat(Sink.head(), Keep.right());

    //#stage-chain
    final RunnableGraph<CompletionStage<List<Integer>>> runnable =
      Source
        .from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        .transform(() -> new Filter<Integer>(elem -> elem % 2 == 0))
        .transform(() -> new Duplicator<Integer>())
        .transform(() -> new Map<Integer, Integer>(elem -> elem / 2))
        .toMat(sink, Keep.right());
    //#stage-chain

    assertEquals(Arrays.asList(1, 1, 2, 2, 3, 3, 4, 4, 5, 5),
        runnable.run(mat).toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  //#detached
  class Buffer2<T> extends DetachedStage<T, T> {
    final private Integer SIZE = 2;
    final private List<T> buf = new ArrayList<>(SIZE);
    private Integer capacity = SIZE;

    private boolean isFull() {
      return capacity == 0;
    }

    private boolean isEmpty() {
      return capacity == SIZE;
    }

    private T dequeue() {
      capacity += 1;
      return buf.remove(0);
    }

    private void enqueue(T elem) {
      capacity -= 1;
      buf.add(elem);
    }

    public DownstreamDirective onPull(DetachedContext<T> ctx) {
      if (isEmpty()) {
        if (ctx.isFinishing()) return ctx.finish(); // No more elements will arrive
        else return ctx.holdDownstream(); // waiting until new elements
      } else {
        final T next = dequeue();
        if (ctx.isHoldingUpstream()) return ctx.pushAndPull(next); // release upstream
        else return ctx.push(next);
      }
    }

    public UpstreamDirective onPush(T elem, DetachedContext<T> ctx) {
      enqueue(elem);
      if (isFull()) return ctx.holdUpstream(); // Queue is now full, wait until new empty slot
      else {
        if (ctx.isHoldingDownstream()) return ctx.pushAndPull(dequeue()); // Release downstream
        else return ctx.pull();
      }
    }

    public TerminationDirective onUpstreamFinish(DetachedContext<T> ctx) {
      if (!isEmpty()) return ctx.absorbTermination(); // still need to flush from buffer
      else return ctx.finish(); // already empty, finishing
    }
  }
  //#detached

  @Test
  public void demonstrateDetachedStage() throws Exception {
    final Pair<TestPublisher.Probe<Integer>,TestSubscriber.Probe<Integer>> pair =
      TestSource.<Integer>probe(system)
        .transform(() -> new Buffer2<Integer>())
        .toMat(TestSink.probe(system), Keep.both())
        .run(mat);

    final TestPublisher.Probe<Integer> pub = pair.first();
    final TestSubscriber.Probe<Integer> sub = pair.second();

    final FiniteDuration timeout = Duration.create(100, TimeUnit.MILLISECONDS);

    sub.request(2);
    sub.expectNoMsg(timeout);

    pub.sendNext(1);
    pub.sendNext(2);
    sub.expectNext(1, 2);

    pub.sendNext(3);
    pub.sendNext(4);
    sub.expectNoMsg(timeout);

    sub.request(2);
    sub.expectNext(3, 4);

    pub.sendComplete();
    sub.expectComplete();
  }

}
