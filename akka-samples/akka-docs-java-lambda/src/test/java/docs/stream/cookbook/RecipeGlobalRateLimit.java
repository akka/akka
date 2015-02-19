/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.*;
import akka.dispatch.Mapper;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Patterns;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.*;
import akka.stream.javadsl.japi.Creator;
import akka.stream.testkit.StreamTestKit;
import akka.testkit.JavaTestKit;
import akka.util.Timeout;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.PartialFunction;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static junit.framework.TestCase.assertTrue;

public class RecipeGlobalRateLimit extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeGlobalRateLimit");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  static
  //#global-limiter-actor
  public class Limiter extends AbstractActor {

    public static class WantToPass { }
    public static final WantToPass WANT_TO_PASS = new WantToPass();

    public static class MayPass { }
    public static final MayPass MAY_PASS = new MayPass();

    public static class ReplenishTokens { }
    public static final ReplenishTokens REPLENISH_TOKENS = new ReplenishTokens();

    private final int maxAvailableTokens;
    private final FiniteDuration tokenRefreshPeriod;
    private final int tokenRefreshAmount;

    private final List<ActorRef> waitQueue = new ArrayList<>();
    private final Cancellable replenishTimer;

    private int permitTokens;


    public static Props props(int maxAvailableTokens, FiniteDuration tokenRefreshPeriod, int tokenRefreshAmount) {
      return Props.create(Limiter.class, maxAvailableTokens, tokenRefreshPeriod, tokenRefreshAmount);
    }

    private Limiter(int maxAvailableTokens, FiniteDuration tokenRefreshPeriod, int tokenRefreshAmount) {
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
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      //#global-limiter-flow
      public <T> Flow<T, T> limitGlobal(ActorRef limiter, FiniteDuration maxAllowedWait) {
        final Flow<T, T> f = Flow.create();

        return f.mapAsync(element -> {
          final Timeout triggerTimeout = new Timeout(maxAllowedWait);
          final Future<Object> limiterTriggerFuture = Patterns.ask(limiter, Limiter.WANT_TO_PASS, triggerTimeout);
          return limiterTriggerFuture.map(new Mapper<Object, T>() {
            @Override public T apply(Object parameter) { return element; }
          }, system.dispatcher());
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

        // TODO why wouldn't the normal () -> e1 not compile here, very weird
        final Source<String> source1 = Source.<String>from((Creator<Iterator<String>>) () -> e1).via(limitGlobal(limiter, twoSeconds));
        final Source<String> source2 = Source.<String>from((Creator<Iterator<String>>) () -> e2).via(limitGlobal(limiter, twoSeconds));

        final StreamTestKit.SubscriberProbe<String> probe = new StreamTestKit.SubscriberProbe<>(system);

        final Merge<String> merge = Merge.<String>create();
        new FlowGraphBuilder()
          .addEdge(source1, merge).addEdge(merge, Sink.create(probe))
          .addEdge(source2, merge)
          .build().run(mat);

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

