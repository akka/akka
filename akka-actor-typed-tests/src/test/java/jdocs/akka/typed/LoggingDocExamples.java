/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// #logMessages
import akka.actor.typed.LogOptions;
import org.slf4j.event.Level;

// #logMessages

public interface LoggingDocExamples {

  // #context-log
  public class MyLoggingBehavior extends AbstractBehavior<String> {

    public static Behavior<String> create() {
      return Behaviors.setup(MyLoggingBehavior::new);
    }

    private final ActorContext<String> context;

    private MyLoggingBehavior(ActorContext<String> context) {
      this.context = context;
    }

    @Override
    public Receive<String> createReceive() {
      return newReceiveBuilder().onMessage(String.class, this::onReceive).build();
    }

    private Behavior<String> onReceive(String message) {
      context.getLog().info("Received message: {}", message);
      return this;
    }
  }
  // #context-log

  // #logger-name
  public class BackendManager extends AbstractBehavior<String> {

    public static Behavior<String> create() {
      return Behaviors.setup(
          context -> {
            context.setLoggerName(BackendManager.class);
            context.getLog().info("Starting up");
            return new BackendManager(context);
          });
    }

    private final ActorContext<String> context;

    private BackendManager(ActorContext<String> context) {
      this.context = context;
    }

    @Override
    public Receive<String> createReceive() {
      return newReceiveBuilder().onMessage(String.class, this::onReceive).build();
    }

    private Behavior<String> onReceive(String message) {
      context.getLog().debug("Received message: {}", message);
      return this;
    }
  }
  // #logger-name

  // #logger-factory
  class BackendTask {
    private final Logger log = LoggerFactory.getLogger(getClass());

    void run() {
      CompletableFuture<String> task =
          CompletableFuture.supplyAsync(
              () -> {
                // some work
                return "result";
              });
      task.whenComplete(
          (result, exc) -> {
            if (exc == null) log.error("Task failed", exc);
            else log.info("Task completed: {}", result);
          });
    }
  }
  // #logger-factory

  static void logMessages() {
    // #logMessages
    Behaviors.logMessages(LogOptions.create().withLevel(Level.TRACE), BackendManager.create());
    // #logMessages
  }

  public class BackendManager2 extends AbstractBehavior<BackendManager2.Command> {

    interface Command {
      String identifier();
    }

    public static Behavior<Command> create() {
      return Behaviors.empty();
    }

    @Override
    public Receive<Command> createReceive() {
      return newReceiveBuilder().build();
    }
  }

  static void withMdc() {
    ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "notUsed");

    // #withMdc
    Map<String, String> staticMdc = new HashMap<>();
    staticMdc.put("startTime", String.valueOf(system.startTime()));

    Behaviors.withMdc(
        BackendManager2.Command.class,
        staticMdc,
        message -> {
          Map<String, String> msgMdc = new HashMap<>();
          msgMdc.put("identifier", message.identifier());
          msgMdc.put("upTime", String.valueOf(system.uptime()));
          return msgMdc;
        },
        BackendManager2.create());
    // #withMdc
  }
}
