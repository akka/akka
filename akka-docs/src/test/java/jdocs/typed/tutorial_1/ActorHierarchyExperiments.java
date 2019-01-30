/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_1;

import akka.actor.typed.PreRestart;
import akka.actor.typed.SupervisorStrategy;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.typed.PostStop;

// #print-refs
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

class PrintMyActorRefActor extends AbstractBehavior<String> {

  static Behavior<String> createBehavior() {
    return Behaviors.setup(PrintMyActorRefActor::new);
  }

  private final ActorContext<String> context;

  private PrintMyActorRefActor(ActorContext<String> context) {
    this.context = context;
  }

  @Override
  public Receive<String> createReceive() {
    return newReceiveBuilder().onMessageEquals("printit", this::printIt).build();
  }

  private Behavior<String> printIt() {
    ActorRef<String> secondRef = context.spawn(Behaviors.empty(), "second-actor");
    System.out.println("Second: " + secondRef);
    return this;
  }
}
// #print-refs

// #start-stop
class StartStopActor1 extends AbstractBehavior<String> {

  static Behavior<String> createBehavior() {
    return Behaviors.setup(context -> new StartStopActor1());
  }

  private StartStopActor1() {
    System.out.println("first started");
  }

  @Override
  public Receive<String> createReceive() {
    return newReceiveBuilder()
        .onMessageEquals("stop", Behaviors::stopped)
        .onSignal(PostStop.class, signal -> postStop())
        .build();
  }

  private Behavior<String> postStop() {
    System.out.println("first stopped");
    return this;
  }
}

class StartStopActor2 extends AbstractBehavior<String> {

  static Behavior<String> createBehavior() {
    return Behaviors.setup(context -> new StartStopActor2());
  }

  private StartStopActor2() {
    System.out.println("second started");
  }

  @Override
  public Receive<String> createReceive() {
    return newReceiveBuilder().onSignal(PostStop.class, signal -> postStop()).build();
  }

  private Behavior<String> postStop() {
    System.out.println("second stopped");
    return this;
  }
}
// #start-stop

// #supervise
class SupervisingActor extends AbstractBehavior<String> {

  static Behavior<String> createBehavior() {
    return Behaviors.setup(SupervisingActor::new);
  }

  private final ActorRef<String> child;

  private SupervisingActor(ActorContext<String> context) {
    child =
        context.spawn(
            Behaviors.supervise(SupervisedActor.createBehavior())
                .onFailure(SupervisorStrategy.restart()),
            "supervised-actor");
  }

  @Override
  public Receive<String> createReceive() {
    return newReceiveBuilder().onMessageEquals("failChild", this::failChild).build();
  }

  private Behavior<String> failChild() {
    child.tell("fail");
    return this;
  }
}

class SupervisedActor extends AbstractBehavior<String> {

  static Behavior<String> createBehavior() {
    return Behaviors.setup(context -> new SupervisedActor());
  }

  private SupervisedActor() {
    System.out.println("supervised actor started");
  }

  @Override
  public Receive<String> createReceive() {
    return newReceiveBuilder()
        .onMessageEquals("fail", this::fail)
        .onSignal(PreRestart.class, signal -> preRestart())
        .onSignal(PostStop.class, signal -> postStop())
        .build();
  }

  private Behavior<String> fail() {
    System.out.println("supervised actor fails now");
    throw new RuntimeException("I failed!");
  }

  private Behavior<String> preRestart() {
    System.out.println("second will be restarted");
    return this;
  }

  private Behavior<String> postStop() {
    System.out.println("second stopped");
    return this;
  }
}
// #supervise

// #print-refs

class Main extends AbstractBehavior<String> {

  static Behavior<String> createBehavior() {
    return Behaviors.setup(Main::new);
  }

  private final ActorContext<String> context;

  private Main(ActorContext<String> context) {
    this.context = context;
  }

  @Override
  public Receive<String> createReceive() {
    return newReceiveBuilder().onMessageEquals("start", this::start).build();
  }

  private Behavior<String> start() {
    ActorRef<String> firstRef = context.spawn(PrintMyActorRefActor.createBehavior(), "first-actor");

    System.out.println("First: " + firstRef);
    firstRef.tell("printit");
    return Behaviors.same();
  }
}

public class ActorHierarchyExperiments {
  public static void main(String[] args) {
    ActorSystem.create(Main.createBehavior(), "testSystem");
  }
}
// #print-refs

class ActorHierarchyExperimentsTest extends JUnitSuite {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testStartAndStopActors() {
    // #start-stop-main
    ActorRef<String> first = testKit.spawn(StartStopActor1.createBehavior(), "first");
    first.tell("stop");
    // #start-stop-main
  }

  @Test
  public void testSuperviseActors() throws Exception {
    // #supervise-main
    ActorRef<String> supervisingActor =
        testKit.spawn(SupervisingActor.createBehavior(), "supervising-actor");
    supervisingActor.tell("failChild");
    // #supervise-main
    Thread.sleep(200); // allow for the println/logging to complete
  }
}
