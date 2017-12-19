/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed.coexistence;

import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Actor;
//#adapter-import
// In java use the static methods on Adapter to convert from typed to untyped
import akka.actor.typed.javadsl.Adapter;
//#adapter-import
import akka.testkit.TestProbe;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import static akka.actor.typed.javadsl.Actor.same;

public class UntypedWatchingTypedTest extends JUnitSuite {

  //#untyped-watch
  public static class Untyped extends AbstractActor {
    public static akka.actor.Props props() {
      return akka.actor.Props.create(Untyped.class);
    }

    private final akka.actor.typed.ActorRef<Typed.Command> second =
      Adapter.spawn(getContext(), Typed.behavior(), "second");

    @Override
    public void preStart() {
      Adapter.watch(getContext(), second);
      second.tell(new Typed.Ping(Adapter.toTyped(getSelf())));
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(Typed.Pong.class, msg -> {
          Adapter.stop(getContext(), second);
        })
        .match(akka.actor.Terminated.class, t -> {
          getContext().stop(getSelf());
        })
        .build();
    }
  }
  //#untyped-watch

  //#typed
  public static abstract class Typed {
    interface Command { }

    public static class Ping implements Command {
      public final akka.actor.typed.ActorRef<Pong> replyTo;

      public Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    public static class Pong { }

    public static Behavior<Command> behavior() {
      return Actor.immutable(Typed.Command.class)
        .onMessage(Typed.Ping.class, (ctx, msg) -> {
          msg.replyTo.tell(new Pong());
          return same();
        })
        .build();
    }
  }
  //#typed

  @Test
  public void testItWorks() {
    //#create-untyped
    ActorSystem as = ActorSystem.apply();
    akka.actor.ActorRef untyped = as.actorOf(Untyped.props());
    //#create-untyped
    TestProbe probe = new TestProbe(as);
    probe.watch(untyped);
    probe.expectTerminated(untyped, Duration.create(1, "second"));
    as.terminate();
  }
}
