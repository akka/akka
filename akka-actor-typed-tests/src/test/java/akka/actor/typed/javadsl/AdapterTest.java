/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import akka.actor.ActorSystem;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Signal;
import akka.actor.typed.Terminated;
import akka.testkit.javadsl.TestKit;
import akka.actor.SupervisorStrategy;
import static akka.actor.typed.javadsl.Behaviors.*;

public class AdapterTest extends JUnitSuite {

  static akka.actor.Props untyped1() {
    return akka.actor.Props.create(Untyped1.class, () -> new Untyped1());
  }

  static class Untyped1 extends akka.actor.AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .matchEquals("ping", s -> getSender().tell("pong", getSelf()))
          .match(
              ThrowIt.class,
              t -> {
                throw t;
              })
          .build();
    }
  }

  static class Typed1 {
    private final akka.actor.ActorRef ref;
    private final akka.actor.ActorRef probe;

    private Typed1(akka.actor.ActorRef ref, akka.actor.ActorRef probe) {
      this.ref = ref;
      this.probe = probe;
    }

    static Behavior<String> create(akka.actor.ActorRef ref, akka.actor.ActorRef probe) {
      Typed1 logic = new Typed1(ref, probe);
      return receive(logic::onMessage, logic::onSignal);
    }

    Behavior<String> onMessage(ActorContext<String> context, String message) {
      if (message.equals("send")) {
        akka.actor.ActorRef replyTo = Adapter.toUntyped(context.getSelf());
        ref.tell("ping", replyTo);
        return same();
      } else if (message.equals("pong")) {
        probe.tell("ok", akka.actor.ActorRef.noSender());
        return same();
      } else if (message.equals("actorOf")) {
        akka.actor.ActorRef child = Adapter.actorOf(context, untyped1());
        child.tell("ping", Adapter.toUntyped(context.getSelf()));
        return same();
      } else if (message.equals("watch")) {
        Adapter.watch(context, ref);
        return same();
      } else if (message.equals("supervise-restart")) {
        // restart is the default, otherwise an intermediate is required
        akka.actor.ActorRef child = Adapter.actorOf(context, untyped1());
        Adapter.watch(context, child);
        child.tell(new ThrowIt3(), Adapter.toUntyped(context.getSelf()));
        child.tell("ping", Adapter.toUntyped(context.getSelf()));
        return same();
      } else if (message.equals("stop-child")) {
        akka.actor.ActorRef child = Adapter.actorOf(context, untyped1());
        Adapter.watch(context, child);
        Adapter.stop(context, child);
        return same();
      } else if (message.equals("stop-self")) {
        try {
          context.stop(context.getSelf());
        } catch (Exception e) {
          probe.tell(e, akka.actor.ActorRef.noSender());
        }
        return same();
      } else {
        return unhandled();
      }
    }

    Behavior<String> onSignal(ActorContext<String> context, Signal sig) {
      if (sig instanceof Terminated) {
        probe.tell("terminated", akka.actor.ActorRef.noSender());
        return same();
      } else {
        return unhandled();
      }
    }
  }

  static interface Typed2Msg {};

  static final class Ping implements Typed2Msg {
    public final ActorRef<String> replyTo;

    public Ping(ActorRef<String> replyTo) {
      this.replyTo = replyTo;
    }
  }

  static final class StopIt implements Typed2Msg {}

  abstract static class ThrowIt extends RuntimeException implements Typed2Msg {}

  static class ThrowIt1 extends ThrowIt {}

  static class ThrowIt2 extends ThrowIt {}

  static class ThrowIt3 extends ThrowIt {}

  static akka.actor.Props untyped2(ActorRef<Ping> ref, akka.actor.ActorRef probe) {
    return akka.actor.Props.create(Untyped2.class, () -> new Untyped2(ref, probe));
  }

  static class Untyped2 extends akka.actor.AbstractActor {
    private final ActorRef<Ping> ref;
    private final akka.actor.ActorRef probe;
    private final SupervisorStrategy strategy;

    Untyped2(ActorRef<Ping> ref, akka.actor.ActorRef probe) {
      this.ref = ref;
      this.probe = probe;
      this.strategy = strategy();
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .matchEquals(
              "send",
              s -> {
                ActorRef<String> replyTo = Adapter.toTyped(getSelf());
                ref.tell(new Ping(replyTo));
              })
          .matchEquals("pong", s -> probe.tell("ok", getSelf()))
          .matchEquals(
              "spawn",
              s -> {
                ActorRef<Typed2Msg> child = Adapter.spawnAnonymous(getContext(), typed2());
                child.tell(new Ping(Adapter.toTyped(getSelf())));
              })
          .matchEquals(
              "actorOf-props",
              s -> {
                // this is how Cluster Sharding can be used
                akka.actor.ActorRef child = getContext().actorOf(typed2Props());
                child.tell(new Ping(Adapter.toTyped(getSelf())), akka.actor.ActorRef.noSender());
              })
          .matchEquals("watch", s -> Adapter.watch(getContext(), ref))
          .match(akka.actor.Terminated.class, t -> probe.tell("terminated", getSelf()))
          .matchEquals("supervise-stop", s -> testSupervice(new ThrowIt1()))
          .matchEquals("supervise-resume", s -> testSupervice(new ThrowIt2()))
          .matchEquals("supervise-restart", s -> testSupervice(new ThrowIt3()))
          .matchEquals(
              "stop-child",
              s -> {
                ActorRef<Typed2Msg> child = Adapter.spawnAnonymous(getContext(), typed2());
                Adapter.watch(getContext(), child);
                Adapter.stop(getContext(), child);
              })
          .build();
    }

    private void testSupervice(ThrowIt t) {
      ActorRef<Typed2Msg> child = Adapter.spawnAnonymous(getContext(), typed2());
      Adapter.watch(getContext(), child);
      child.tell(t);
      child.tell(new Ping(Adapter.toTyped(getSelf())));
    }

    private SupervisorStrategy strategy() {
      return new akka.actor.OneForOneStrategy(
          false,
          akka.japi.pf.DeciderBuilder.match(
                  ThrowIt1.class,
                  e -> {
                    probe.tell("thrown-stop", getSelf());
                    return SupervisorStrategy.stop();
                  })
              .match(
                  ThrowIt2.class,
                  e -> {
                    probe.tell("thrown-resume", getSelf());
                    return SupervisorStrategy.resume();
                  })
              .match(
                  ThrowIt3.class,
                  e -> {
                    probe.tell("thrown-restart", getSelf());
                    // TODO Restart will not really restart the behavior
                    return SupervisorStrategy.restart();
                  })
              .build());
    }

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }
  }

  static Behavior<Typed2Msg> typed2() {
    return Behaviors.receive(
        (context, message) -> {
          if (message instanceof Ping) {
            ActorRef<String> replyTo = ((Ping) message).replyTo;
            replyTo.tell("pong");
            return same();
          } else if (message instanceof StopIt) {
            return stopped();
          } else if (message instanceof ThrowIt) {
            throw (ThrowIt) message;
          } else {
            return unhandled();
          }
        });
  }

  static akka.actor.Props typed2Props() {
    return Adapter.props(() -> typed2());
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("ActorSelectionTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void shouldSendMessageFromTypedToUntyped() {
    TestKit probe = new TestKit(system);
    akka.actor.ActorRef untypedRef = system.actorOf(untyped1());
    ActorRef<String> typedRef =
        Adapter.spawnAnonymous(system, Typed1.create(untypedRef, probe.getRef()));
    typedRef.tell("send");
    probe.expectMsg("ok");
  }

  @Test
  public void shouldSendMessageFromUntypedToTyped() {
    TestKit probe = new TestKit(system);
    ActorRef<Ping> typedRef = Adapter.spawnAnonymous(system, typed2()).narrow();
    akka.actor.ActorRef untypedRef = system.actorOf(untyped2(typedRef, probe.getRef()));
    untypedRef.tell("send", akka.actor.ActorRef.noSender());
    probe.expectMsg("ok");
  }

  @Test
  public void shouldSpawnTypedChildFromUntypedParent() {
    TestKit probe = new TestKit(system);
    ActorRef<Ping> ignore = Adapter.spawnAnonymous(system, ignore());
    akka.actor.ActorRef untypedRef = system.actorOf(untyped2(ignore, probe.getRef()));
    untypedRef.tell("spawn", akka.actor.ActorRef.noSender());
    probe.expectMsg("ok");
  }

  @Test
  public void shouldActorOfTypedChildViaPropsFromUntypedParent() {
    TestKit probe = new TestKit(system);
    ActorRef<Ping> ignore = Adapter.spawnAnonymous(system, ignore());
    akka.actor.ActorRef untypedRef = system.actorOf(untyped2(ignore, probe.getRef()));
    untypedRef.tell("actorOf-props", akka.actor.ActorRef.noSender());
    probe.expectMsg("ok");
  }

  @Test
  public void shouldActorOfUntypedChildFromTypedParent() {
    TestKit probe = new TestKit(system);
    akka.actor.ActorRef ignore = system.actorOf(akka.actor.Props.empty());
    ActorRef<String> typedRef =
        Adapter.spawnAnonymous(system, Typed1.create(ignore, probe.getRef()));
    typedRef.tell("actorOf");
    probe.expectMsg("ok");
  }

  @Test
  public void shouldWatchTypedFromUntyped() {
    TestKit probe = new TestKit(system);
    ActorRef<Typed2Msg> typedRef = Adapter.spawnAnonymous(system, typed2());
    ActorRef<Ping> typedRef2 = typedRef.narrow();
    akka.actor.ActorRef untypedRef = system.actorOf(untyped2(typedRef2, probe.getRef()));
    untypedRef.tell("watch", akka.actor.ActorRef.noSender());
    typedRef.tell(new StopIt());
    probe.expectMsg("terminated");
  }

  @Test
  public void shouldWatchUntypedFromTyped() {
    TestKit probe = new TestKit(system);
    akka.actor.ActorRef untypedRef = system.actorOf(untyped1());
    ActorRef<String> typedRef =
        Adapter.spawnAnonymous(system, Typed1.create(untypedRef, probe.getRef()));
    typedRef.tell("watch");
    untypedRef.tell(akka.actor.PoisonPill.getInstance(), akka.actor.ActorRef.noSender());
    probe.expectMsg("terminated");
  }

  @Test
  public void shouldSuperviseUntypedChildAsRestartFromTypedParent() {
    TestKit probe = new TestKit(system);
    akka.actor.ActorRef ignore = system.actorOf(akka.actor.Props.empty());
    ActorRef<String> typedRef =
        Adapter.spawnAnonymous(system, Typed1.create(ignore, probe.getRef()));

    int originalLogLevel = system.getEventStream().logLevel();
    try {
      // suppress the logging with stack trace
      system.getEventStream().setLogLevel(Integer.MIN_VALUE); // OFF

      typedRef.tell("supervise-restart");
      probe.expectMsg("ok");
    } finally {
      system.getEventStream().setLogLevel(originalLogLevel);
    }
    probe.expectNoMessage(Duration.ofMillis(100)); // no pong
  }

  @Test
  public void shouldStopTypedChildFromUntypedParent() {
    TestKit probe = new TestKit(system);
    ActorRef<Ping> ignore = Adapter.spawnAnonymous(system, ignore());
    akka.actor.ActorRef untypedRef = system.actorOf(untyped2(ignore, probe.getRef()));
    untypedRef.tell("stop-child", akka.actor.ActorRef.noSender());
    probe.expectMsg("terminated");
  }

  @Test
  public void shouldStopUntypedChildFromTypedParent() {
    TestKit probe = new TestKit(system);
    akka.actor.ActorRef ignore = system.actorOf(akka.actor.Props.empty());
    ActorRef<String> typedRef =
        Adapter.spawnAnonymous(system, Typed1.create(ignore, probe.getRef()));
    typedRef.tell("stop-child");
    probe.expectMsg("terminated");
  }

  @Test
  public void stopSelfWillCauseError() {
    TestKit probe = new TestKit(system);
    akka.actor.ActorRef ignore = system.actorOf(akka.actor.Props.empty());
    ActorRef<String> typedRef =
        Adapter.spawnAnonymous(system, Typed1.create(ignore, probe.getRef()));
    typedRef.tell("stop-self");
    probe.expectMsgClass(IllegalArgumentException.class);
  }
}
