/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.coexistence;

import akka.actor.AbstractActor;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
// #adapter-import
// In java use the static methods on Adapter to convert from typed to untyped
import akka.actor.typed.javadsl.Adapter;
// #adapter-import
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import static akka.actor.typed.javadsl.Behaviors.same;

public class UntypedWatchingTypedTest extends JUnitSuite {

  // #untyped-watch
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
          .match(
              Typed.Pong.class,
              message -> {
                Adapter.stop(getContext(), second);
              })
          .match(
              akka.actor.Terminated.class,
              t -> {
                getContext().stop(getSelf());
              })
          .build();
    }
  }
  // #untyped-watch

  // #typed
  public abstract static class Typed {
    interface Command {}

    public static class Ping implements Command {
      public final akka.actor.typed.ActorRef<Pong> replyTo;

      public Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    public static class Pong {}

    public static Behavior<Command> behavior() {
      return Behaviors.receive(Typed.Command.class)
          .onMessage(
              Typed.Ping.class,
              (context, message) -> {
                message.replyTo.tell(new Pong());
                return same();
              })
          .build();
    }
  }
  // #typed

  @Test
  public void testItWorks() {
    // #create-untyped
    akka.actor.ActorSystem as = akka.actor.ActorSystem.create();
    akka.actor.ActorRef untyped = as.actorOf(Untyped.props());
    // #create-untyped
    TestProbe probe = new TestProbe(as);
    probe.watch(untyped);
    probe.expectTerminated(untyped, Duration.create(1, "second"));
    TestKit.shutdownActorSystem(as);
  }

  @Test
  public void testConversionFromUnTypedSystemToTyped() {
    // #convert-untyped
    akka.actor.ActorSystem untypedActorSystem = akka.actor.ActorSystem.create();
    ActorSystem<Void> typedActorSystem = Adapter.toTyped(untypedActorSystem);
    // #convert-untyped
    TestKit.shutdownActorSystem(untypedActorSystem);
  }
}
