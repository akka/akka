/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.DetachedContext;
import akka.stream.stage.DetachedStage;
import akka.stream.stage.DownstreamDirective;
import akka.stream.stage.UpstreamDirective;
import akka.stream.testkit.StreamTestKit;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Subscription;
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

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

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

      @Override public DownstreamDirective onPull(DetachedContext<T> ctx) {
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
      if (ctx.isHolding()) {
        return ctx.pushAndPull(currentValue);
      } else {
        return ctx.pull();
      }
    }

    @Override
    public DownstreamDirective onPull(DetachedContext<T> ctx) {
      if (waitingFirstValue) {
        return ctx.hold();
      } else {
        return ctx.push(currentValue);
      }
    }
  }
    //#hold-version-2

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        StreamTestKit.PublisherProbe<Integer> pub = new StreamTestKit.PublisherProbe<>(system);
        StreamTestKit.SubscriberProbe<Integer> sub = new StreamTestKit.SubscriberProbe<>(system);
        Source<Integer> source = Source.from(pub);
        Sink<Integer> sink = Sink.create(sub);

        source.transform(() -> new HoldWithInitial<>(0)).to(sink).run(mat);

        StreamTestKit.AutoPublisher<Integer> manualSource = new StreamTestKit.AutoPublisher<Integer>(pub, 0);

        FiniteDuration timeout = FiniteDuration.create(200, TimeUnit.MILLISECONDS);

        Subscription subscription = sub.expectSubscription();
        sub.expectNoMsg(timeout);

        subscription.request(1);
        sub.expectNext(0);

        subscription.request(1);
        sub.expectNext(0);

        manualSource.sendNext(1);
        manualSource.sendNext(2);

        subscription.request(2);
        sub.expectNext(2);
        sub.expectNext(2);

        manualSource.sendComplete();
        subscription.request(1);
        sub.expectComplete();
      }
    };
  }

  @Test
  public void workForVersion2() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        StreamTestKit.PublisherProbe<Integer> pub = new StreamTestKit.PublisherProbe<>(system);
        StreamTestKit.SubscriberProbe<Integer> sub = new StreamTestKit.SubscriberProbe<>(system);
        Source<Integer> source = Source.from(pub);
        Sink<Integer> sink = Sink.create(sub);

        source.transform(() -> new HoldWithWait<>()).to(sink).run(mat);

        StreamTestKit.AutoPublisher<Integer> manualSource = new StreamTestKit.AutoPublisher<Integer>(pub, 0);

        FiniteDuration timeout = FiniteDuration.create(200, TimeUnit.MILLISECONDS);

        Subscription subscription = sub.expectSubscription();
        sub.expectNoMsg(timeout);

        subscription.request(1);
        sub.expectNoMsg(timeout);

        manualSource.sendNext(1);
        sub.expectNext(1);

        manualSource.sendNext(2);
        manualSource.sendNext(3);

        subscription.request(2);
        sub.expectNext(3);
        sub.expectNext(3);

        manualSource.sendComplete();
        subscription.request(1);
        sub.expectComplete();
      }
    };
  }

}

