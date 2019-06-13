/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.coexistence;

import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
// #adapter-import
// in Java use the static methods on Adapter to convert from untyped to typed
import akka.actor.typed.javadsl.Adapter;
// #adapter-import
import akka.testkit.javadsl.TestKit;
import akka.testkit.TestProbe;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import static akka.actor.typed.javadsl.Behaviors.same;
import static akka.actor.typed.javadsl.Behaviors.stopped;

public class TypedWatchingUntypedTest extends JUnitSuite {

  // #typed
  public abstract static class Typed {
    public static class Ping {
      public final akka.actor.typed.ActorRef<Pong> replyTo;

      public Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    interface Command {}

    public static class Pong implements Command {}

    public static Behavior<Command> behavior() {
      return akka.actor.typed.javadsl.Behaviors.setup(
          context -> {
            akka.actor.ActorRef second = Adapter.actorOf(context, Untyped.props(), "second");

            Adapter.watch(context, second);

            second.tell(
                new Typed.Ping(context.getSelf().narrow()), Adapter.toUntyped(context.getSelf()));

            return akka.actor.typed.javadsl.Behaviors.receive(Typed.Command.class)
                .onMessage(
                    Typed.Pong.class,
                    message -> {
                      Adapter.stop(context, second);
                      return same();
                    })
                .onSignal(akka.actor.typed.Terminated.class, sig -> stopped())
                .build();
          });
    }
  }
  // #typed

  // #untyped
  public static class Untyped extends AbstractActor {
    public static akka.actor.Props props() {
      return akka.actor.Props.create(Untyped.class);
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              Typed.Ping.class,
              message -> {
                message.replyTo.tell(new Typed.Pong());
              })
          .build();
    }
  }
  // #untyped

  @Test
  public void testItWorks() {
    // #create
    ActorSystem as = ActorSystem.create();
    ActorRef<Typed.Command> typed = Adapter.spawn(as, Typed.behavior(), "Typed");
    // #create
    TestProbe probe = new TestProbe(as);
    probe.watch(Adapter.toUntyped(typed));
    probe.expectTerminated(Adapter.toUntyped(typed), Duration.create(1, "second"));
    TestKit.shutdownActorSystem(as);
  }
}
