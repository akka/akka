/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.fromclassic;

// #hello-world-actor
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

// #hello-world-actor

interface TypedSample {

  // #hello-world-actor
  public class HelloWorld extends AbstractBehavior<HelloWorld.Greet> {

    public static final class Greet {
      public final String whom;
      public final ActorRef<Greeted> replyTo;

      public Greet(String whom, ActorRef<Greeted> replyTo) {
        this.whom = whom;
        this.replyTo = replyTo;
      }
    }

    public static final class Greeted {
      public final String whom;
      public final ActorRef<Greet> from;

      public Greeted(String whom, ActorRef<Greet> from) {
        this.whom = whom;
        this.from = from;
      }
    }

    public static Behavior<Greet> create() {
      return Behaviors.setup(HelloWorld::new);
    }

    private final ActorContext<Greet> context;

    private HelloWorld(ActorContext<Greet> context) {
      this.context = context;
    }

    @Override
    public Receive<Greet> createReceive() {
      return newReceiveBuilder().onMessage(Greet.class, this::onGreet).build();
    }

    private Behavior<Greet> onGreet(Greet command) {
      context.getLog().info("Hello {}!", command.whom);
      command.replyTo.tell(new Greeted(command.whom, context.getSelf()));
      return this;
    }
  }
  // #hello-world-actor
}
