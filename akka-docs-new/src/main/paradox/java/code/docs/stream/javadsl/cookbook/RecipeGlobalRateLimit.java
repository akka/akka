/**
 *  Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package docs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.*;
import akka.dispatch.Mapper;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.PatternsCS;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.testkit.JavaTestKit;
import akka.util.Timeout;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.PartialFunction;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;


import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static junit.framework.TestCase.assertTrue;

public class RecipeGlobalRateLimit extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeGlobalRateLimit");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  static
  //#global-limiter-actor
  public class Limiter extends AbstractActor {

    public static class WantToPass {}
    public static final WantToPass WANT_TO_PASS = new WantToPass();

    public static class MayPass {}
    public static final MayPass MAY_PASS = new MayPass();

    public static class ReplenishTokens {}
    public static final ReplenishTokens REPLENISH_TOKENS = new ReplenishTokens();

    private final int maxAvailableTokens;
    private final FiniteDuration tokenRefreshPeriod;
    private final int tokenRefreshAmount;

    private final List<ActorRef> waitQueue = new ArrayList<>();
    private final Cancellable replenishTimer;

    private int permitTokens;

    public static Props props(int maxAvailableTokens, FiniteDuration tokenRefreshPeriod,
        int tokenRefreshAmount) {
      return Props.create(Limiter.class, maxAvailableTokens, tokenRefreshPeriod,
        tokenRefreshAmount);
    }

    private Limiter(int maxAvailableTokens, FiniteDuration tokenRefreshPeriod,
        int tokenRefreshAmount) {
      this.maxAvailableTokens = maxAvailableTokens;
      this.tokenRefreshPeriod = tokenRefreshPeriod;
      this.tokenRefreshAmount = tokenRefreshAmount;
      this.permitTokens = maxAvailableTokens;

      this.replenishTimer = system.scheduler().schedule(
        this.tokenRefreshPeriod,
        this.tokenRefreshPeriod,
        self(),
        REPLENISH_TOKENS,
        context().system().dispatcher(),
        self());

      receive(open());
    }

    PartialFunction<Object, BoxedUnit> open() {
      return ReceiveBuilder
        .match(ReplenishTokens.class, rt -> {
          permitTokens = Math.min(permitTokens + tokenRefreshAmount, maxAvailableTokens);
        })
        .match(WantToPass.class, wtp -> {
          permitTokens -= 1;
          sender().tell(MAY_PASS, self());
          if (permitTokens == 0) {
            context().become(closed());
          }
        }).build();
    }

    PartialFunction<Object, BoxedUnit> closed() {
      return ReceiveBuilder
        .match(ReplenishTokens.class, rt -> {
          permitTokens = Math.min(permitTokens + tokenRefreshAmount, maxAvailableTokens);
          releaseWaiting();
        })
        .match(WantToPass.class, wtp -> {
          waitQueue.add(sender());
        })
        .build();
    }

    private void releaseWaiting() {
      final List<ActorRef> toBeReleased = new ArrayList<>(permitTokens);
      for (int i = 0; i < permitTokens && i < waitQueue.size(); i++) {
        toBeReleased.add(waitQueue.remove(i));
      }

      permitTokens -= toBeReleased.size();
      toBeReleased.stream().forEach(ref -> ref.tell(MAY_PASS, self()));
      if (permitTokens > 0) {
        context().become(open());
      }
    }

    @Override
    public void postStop() {
      replenishTimer.cancel();
      waitQueue.stream().forEach(ref -> {
        ref.tell(new Status.Failure(new IllegalStateException("limiter stopped")), self());
      });
    }
  }
  //#global-limiter-actor

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      //#global-limiter-flow
      public <T> Flow<T, T, NotUsed> limitGlobal(ActorRef limiter, FiniteDuration maxAllowedWait) {
        final int parallelism = 4;
        final Flow<T, T, NotUsed> f = Flow.create();

        return f.mapAsync(parallelism, element -> {
          final Timeout triggerTimeout = new Timeout(maxAllowedWait);
          final CompletionStage<Object> limiterTriggerFuture =
            PatternsCS.ask(limiter, Limiter.WANT_TO_PASS, triggerTimeout);
          return limiterTriggerFuture.thenApplyAsync(response -> element, system.dispatcher());
        });
      }
      //#global-limiter-flow

      {
        // Use a large period and emulate the timer by hand instead
        ActorRef limiter = system.actorOf(Limiter.props(2, new FiniteDuration(100, TimeUnit.DAYS), 1), "limiter");

        final Iterator<String> e1 = new Iterator<String>() {
          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public String next() {
            return "E1";
          }
        };
        final Iterator<String> e2 = new Iterator<String>() {
          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public String next() {
            return "E2";
          }
        };

        final FiniteDuration twoSeconds = Duration.create(2, TimeUnit.SECONDS);

        final Sink<String, TestSubscriber.Probe<String>> sink = TestSink.probe(system);
        final TestSubscriber.Probe<String> probe =
          RunnableGraph.<TestSubscriber.Probe<String>>fromGraph(
            GraphDSL.create(sink, (builder, s) -> {
              final int inputPorts = 2;
              final UniformFanInShape<String, String> merge = builder.add(Merge.create(inputPorts));

              final SourceShape<String> source1 =
                builder.add(Source.<String>fromIterator(() -> e1).via(limitGlobal(limiter, twoSeconds)));
              final SourceShape<String> source2 =
                builder.add(Source.<String>fromIterator(() -> e2).via(limitGlobal(limiter, twoSeconds)));

              builder.from(source1).toFanIn(merge);
              builder.from(source2).toFanIn(merge);
              builder.from(merge).to(s);
              return ClosedShape.getInstance();
            })
          ).run(mat);

        probe.expectSubscription().request(1000);

        FiniteDuration fiveHundredMillis = FiniteDuration.create(500, TimeUnit.MILLISECONDS);

        assertTrue(probe.expectNext().startsWith("E"));
        assertTrue(probe.expectNext().startsWith("E"));
        probe.expectNoMsg(fiveHundredMillis);

        limiter.tell(Limiter.REPLENISH_TOKENS, getTestActor());
        assertTrue(probe.expectNext().startsWith("E"));
        probe.expectNoMsg(fiveHundredMillis);

        final Set<String> resultSet = new HashSet<>();
        for (int i = 0; i < 100; i++) {
          limiter.tell(Limiter.REPLENISH_TOKENS, getTestActor());
          resultSet.add(probe.expectNext());
        }

        assertTrue(resultSet.contains("E1"));
        assertTrue(resultSet.contains("E2"));

        probe.expectError();
      }
    };
  }
}
