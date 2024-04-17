package com.example;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

// #hello-world-bot
public class HelloWorldBot extends AbstractBehavior<HelloWorld.Greeted> {

  public static Behavior<HelloWorld.Greeted> create(int max) {
    return Behaviors.setup(context -> new HelloWorldBot(context, max));
  }

  private final int max;
  private int greetingCounter;

  private HelloWorldBot(ActorContext<HelloWorld.Greeted> context, int max) {
    super(context);
    this.max = max;
  }

  @Override
  public Receive<HelloWorld.Greeted> createReceive() {
    return newReceiveBuilder().onMessage(HelloWorld.Greeted.class, this::onGreeted).build();
  }

  private Behavior<HelloWorld.Greeted> onGreeted(HelloWorld.Greeted message) {
    greetingCounter++;
    getContext().getLog().info("Greeting {} for {}", greetingCounter, message.from());
    if (greetingCounter == max) {
      return Behaviors.stopped();
    } else {
      message.from().tell(new HelloWorld.Greet(message.whom(), getContext().getSelf()));
      return this;
    }
  }
}
// #hello-world-bot
