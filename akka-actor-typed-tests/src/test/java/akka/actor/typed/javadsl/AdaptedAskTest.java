/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.testkit.typed.TestKitSettings;
import akka.testkit.typed.javadsl.TestProbe;
import akka.util.Timeout;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class AdaptedAskTest extends JUnitSuite {

  interface Protocol {}
  public static class TriggerPing implements Protocol {
    public final int id;
    public TriggerPing(int id) {
      this.id = id;
    }
  }
  public static class GotPong implements Protocol {
    public final int id;
    public GotPong(int id) {
      this.id = id;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      GotPong gotPong = (GotPong) o;
      return id == gotPong.id;
    }

    @Override
    public int hashCode() {
      return Objects.hash(id);
    }
  }
  public static class GotFailure implements Protocol {
    public final int id;
    public GotFailure(int id) {
      this.id = id;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      GotFailure that = (GotFailure) o;
      return id == that.id;
    }

    @Override
    public int hashCode() {
      return Objects.hash(id);
    }
  }


  interface OtherActorProtocol {}
  static final class Ping implements OtherActorProtocol {
    public final ActorRef<Pong> respondTo;
    public Ping(ActorRef<Pong> respondTo) {
      this.respondTo = respondTo;
    }
  }
  static final class Pong {}

  public static Behavior<OtherActorProtocol> otherActorBehavior = Actor.immutable((ctx, msg) -> {
    if (msg instanceof Ping) {
      ((Ping)msg).respondTo.tell(new Pong());
      return Actor.same();
    } else {
      return Actor.unhandled();
    }
  });

  public static Behavior<Protocol> createBehaviour(ActorRef<OtherActorProtocol> otherActor, ActorRef<Object> probe, Timeout timeout) {
    return Actor.immutable((ctx, msg) -> {
      if (msg instanceof TriggerPing) {
        final int id = ((TriggerPing) msg).id;
        ctx.ask(
            otherActor, Pong.class,
            Ping::new,
            (Pong pong) -> new GotPong(id),
            (Throwable error) -> new GotFailure(id),
            timeout
        );
        return Actor.same();
      } else if (msg instanceof GotPong || msg instanceof GotFailure) {
        probe.tell(msg);
        return Actor.same();
      } else {
        return Actor.unhandled();
      }
    });
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("ActorSelectionTest",
      AkkaSpec.testConf());

  private final akka.actor.ActorSystem system = actorSystemResource.getSystem();
  private final TestKitSettings settings = new TestKitSettings(system.settings().config());

  @Test
  public void askingAnotherActor() {
    ActorRef<OtherActorProtocol> other = Adapter.spawnAnonymous(system, otherActorBehavior);
    TestProbe<Object> probe = new TestProbe<>(Adapter.toTyped(system), settings);
    Timeout timeout = Timeout.apply(10, TimeUnit.SECONDS);
    ActorRef<Protocol> actor = Adapter.spawnAnonymous(system, createBehaviour(other, probe.ref(), timeout));

    actor.tell(new TriggerPing(1));
    probe.expectMsg(new GotPong(1));

  }

  @Test
  public void timingOut() {
    ActorRef<OtherActorProtocol> other = Adapter.spawnAnonymous(system, Actor.ignore());
    TestProbe<Object> probe = new TestProbe<>(Adapter.toTyped(system), settings);
    Timeout timeout = Timeout.apply(10, TimeUnit.MILLISECONDS);
    ActorRef<Protocol> actor = Adapter.spawnAnonymous(system, createBehaviour(other, probe.ref(), timeout));

    actor.tell(new TriggerPing(1));
    probe.expectMsg(new GotFailure(1));
  }
}
