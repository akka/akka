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
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.testkit.javadsl.TestKit;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class TypedWatchingUntypedTest extends JUnitSuite {

  // #typed
  public static class Typed extends AbstractBehavior<Typed.Command> {

    public static class Ping {
      public final akka.actor.typed.ActorRef<Pong> replyTo;

      public Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    interface Command {}

    public enum Pong implements Command {
      INSTANCE
    }

    private final akka.actor.typed.javadsl.ActorContext<Command> context;
    private final akka.actor.ActorRef second;

    private Typed(ActorContext<Command> context, akka.actor.ActorRef second) {
      this.context = context;
      this.second = second;
    }

    public static Behavior<Command> create() {
      return akka.actor.typed.javadsl.Behaviors.setup(
          context -> {
            akka.actor.ActorRef second = Adapter.actorOf(context, Untyped.props(), "second");

            Adapter.watch(context, second);

            second.tell(
                new Typed.Ping(context.getSelf().narrow()), Adapter.toUntyped(context.getSelf()));

            return new Typed(context, second);
          });
    }

    @Override
    public Receive<Command> createReceive() {
      return newReceiveBuilder()
          .onMessage(Typed.Pong.class, message -> onPong())
          .onSignal(akka.actor.typed.Terminated.class, sig -> Behaviors.stopped())
          .build();
    }

    private Behavior<Command> onPong() {
      Adapter.stop(context, second);
      return this;
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
      return receiveBuilder().match(Typed.Ping.class, this::onPing).build();
    }

    private void onPing(Typed.Ping message) {
      message.replyTo.tell(Typed.Pong.INSTANCE);
    }
  }
  // #untyped

  @Test
  public void testItWorks() {
    // #create
    ActorSystem as = ActorSystem.create();
    ActorRef<Typed.Command> typed = Adapter.spawn(as, Typed.create(), "Typed");
    // #create
    TestKit probe = new TestKit(as);
    probe.watch(Adapter.toUntyped(typed));
    probe.expectTerminated(Adapter.toUntyped(typed));
    TestKit.shutdownActorSystem(as);
  }
}
