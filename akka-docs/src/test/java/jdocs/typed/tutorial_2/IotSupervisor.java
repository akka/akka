/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_2;

//#iot-supervisor
import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

public class IotSupervisor extends AbstractBehavior<Void> {

  public static Behavior<Void> createBehavior() {
    return Behaviors.setup(IotSupervisor::new);
  }

  private final ActorContext<Void> context;

  public IotSupervisor(ActorContext<Void> context) {
    this.context = context;
    context.getLog().info("IoT Application started");
  }

  // No need to handle any messages
  @Override
  public Receive<Void> createReceive() {
    return receiveBuilder()
      .onSignal(PostStop.class, signal -> postStop())
      .build();
  }

  private IotSupervisor postStop() {
    context.getLog().info("IoT Application stopped");
    return this;
  }

}
//#iot-supervisor
