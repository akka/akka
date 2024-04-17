package com.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

// #hello-world-main
public class HelloWorldMain extends AbstractBehavior<HelloWorldMain.SayHello> {
  // #hello-world-main

  public static void main(String[] args) throws Exception {
    // #hello-world
    final ActorSystem<SayHello> system =
        ActorSystem.create(HelloWorldMain.create(), "hello");

    system.tell(new HelloWorldMain.SayHello("World"));
    system.tell(new HelloWorldMain.SayHello("Akka"));
    // #hello-world

    Thread.sleep(3000);
    system.terminate();
  }
  // #hello-world-main

  public static record SayHello(String name) {}

  public static Behavior<SayHello> create() {
    return Behaviors.setup(HelloWorldMain::new);
  }

  private final ActorRef<HelloWorld.Greet> greeter;

  private HelloWorldMain(ActorContext<SayHello> context) {
    super(context);
    greeter = context.spawn(HelloWorld.create(), "greeter");
  }

  @Override
  public Receive<SayHello> createReceive() {
    return newReceiveBuilder().onMessage(SayHello.class, this::onSayHello).build();
  }

  private Behavior<SayHello> onSayHello(SayHello command) {
    ActorRef<HelloWorld.Greeted> replyTo =
        getContext().spawn(HelloWorldBot.create(3), command.name);
    greeter.tell(new HelloWorld.Greet(command.name, replyTo));
    return this;
  }
}
// #hello-world-main
