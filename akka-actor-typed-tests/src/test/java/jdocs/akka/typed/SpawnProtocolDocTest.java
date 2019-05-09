/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import jdocs.akka.typed.IntroTest.HelloWorld;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

// #imports1
import akka.actor.typed.Behavior;
import akka.actor.typed.SpawnProtocol;
import akka.actor.typed.javadsl.Behaviors;

// #imports1

// #imports2
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.AskPattern;
import akka.util.Timeout;

// #imports2

public class SpawnProtocolDocTest {

  // #main
  public abstract static class HelloWorldMain {
    private HelloWorldMain() {}

    public static final Behavior<SpawnProtocol> main =
        Behaviors.setup(
            context -> {
              // Start initial tasks
              // context.spawn(...)

              return SpawnProtocol.behavior();
            });
  }
  // #main

  public static void main(String[] args) throws Exception {
    // #system-spawn
    final ActorSystem<SpawnProtocol> system = ActorSystem.create(HelloWorldMain.main, "hello");
    final Duration timeout = Duration.ofSeconds(3);

    CompletionStage<ActorRef<HelloWorld.Greet>> greeter =
        AskPattern.ask(
            system,
            replyTo ->
                new SpawnProtocol.Spawn<>(HelloWorld.greeter, "greeter", Props.empty(), replyTo),
            timeout,
            system.scheduler());

    Behavior<HelloWorld.Greeted> greetedBehavior =
        Behaviors.receive(
            (context, message) -> {
              context.getLog().info("Greeting for {} from {}", message.whom, message.from);
              return Behaviors.stopped();
            });

    CompletionStage<ActorRef<HelloWorld.Greeted>> greetedReplyTo =
        AskPattern.ask(
            system,
            replyTo -> new SpawnProtocol.Spawn<>(greetedBehavior, "", Props.empty(), replyTo),
            timeout,
            system.scheduler());

    greeter.whenComplete(
        (greeterRef, exc) -> {
          if (exc == null) {
            greetedReplyTo.whenComplete(
                (greetedReplyToRef, exc2) -> {
                  if (exc2 == null) {
                    greeterRef.tell(new HelloWorld.Greet("Akka", greetedReplyToRef));
                  }
                });
          }
        });

    // #system-spawn

    Thread.sleep(3000);
    system.terminate();
  }
}
