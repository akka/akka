/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

// #print-refs
package com.example;

// #print-refs

import akka.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

// #print-refs
import akka.actor.AbstractActor;
import akka.actor.AbstractActor.Receive;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

class PrintMyActorRefActor extends AbstractActor {
  static Props props() {
    return Props.create(PrintMyActorRefActor.class, PrintMyActorRefActor::new);
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(
            "printit",
            p -> {
              ActorRef secondRef = getContext().actorOf(Props.empty(), "second-actor");
              System.out.println("Second: " + secondRef);
            })
        .build();
  }
}
// #print-refs

// #start-stop
class StartStopActor1 extends AbstractActor {
  static Props props() {
    return Props.create(StartStopActor1.class, StartStopActor1::new);
  }

  @Override
  public void preStart() {
    System.out.println("first started");
    getContext().actorOf(StartStopActor2.props(), "second");
  }

  @Override
  public void postStop() {
    System.out.println("first stopped");
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(
            "stop",
            s -> {
              getContext().stop(getSelf());
            })
        .build();
  }
}

class StartStopActor2 extends AbstractActor {

  static Props props() {
    return Props.create(StartStopActor2.class, StartStopActor2::new);
  }

  @Override
  public void preStart() {
    System.out.println("second started");
  }

  @Override
  public void postStop() {
    System.out.println("second stopped");
  }

  // Actor.emptyBehavior is a useful placeholder when we don't
  // want to handle any messages in the actor.
  @Override
  public Receive createReceive() {
    return receiveBuilder().build();
  }
}
// #start-stop

// #supervise
class SupervisingActor extends AbstractActor {
  static Props props() {
    return Props.create(SupervisingActor.class, SupervisingActor::new);
  }

  ActorRef child = getContext().actorOf(SupervisedActor.props(), "supervised-actor");

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(
            "failChild",
            f -> {
              child.tell("fail", getSelf());
            })
        .build();
  }
}

class SupervisedActor extends AbstractActor {
  static Props props() {
    return Props.create(SupervisedActor.class, SupervisedActor::new);
  }

  @Override
  public void preStart() {
    System.out.println("supervised actor started");
  }

  @Override
  public void postStop() {
    System.out.println("supervised actor stopped");
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(
            "fail",
            f -> {
              System.out.println("supervised actor fails now");
              throw new Exception("I failed!");
            })
        .build();
  }
}
// #supervise

// #print-refs
public class ActorHierarchyExperiments {
  public static void main(String[] args) throws java.io.IOException {
    ActorSystem system = ActorSystem.create("testSystem");

    ActorRef firstRef = system.actorOf(PrintMyActorRefActor.props(), "first-actor");
    System.out.println("First: " + firstRef);
    firstRef.tell("printit", ActorRef.noSender());

    System.out.println(">>> Press ENTER to exit <<<");
    try {
      System.in.read();
    } finally {
      system.terminate();
    }
  }
}
// #print-refs

class ActorHierarchyExperimentsTest extends JUnitSuite {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create();
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void testStartAndStopActors() {
    // #start-stop-main
    ActorRef first = system.actorOf(StartStopActor1.props(), "first");
    first.tell("stop", ActorRef.noSender());
    // #start-stop-main
  }

  @Test
  public void testSuperviseActors() {
    // #supervise-main
    ActorRef supervisingActor = system.actorOf(SupervisingActor.props(), "supervising-actor");
    supervisingActor.tell("failChild", ActorRef.noSender());
    // #supervise-main
  }
}
