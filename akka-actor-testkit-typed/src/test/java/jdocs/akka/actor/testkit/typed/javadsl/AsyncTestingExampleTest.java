/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.actor.testkit.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import org.junit.AfterClass;
import org.junit.Test;

import java.time.Duration;
import java.util.Objects;

import static org.junit.Assert.assertEquals;

//#test-header
public class AsyncTestingExampleTest {
  final static ActorTestKit testKit = ActorTestKit.create();
//#test-header

  //#under-test
  public static class Ping {
    private String message;
    private ActorRef<Pong> replyTo;

    public Ping(String message, ActorRef<Pong> replyTo) {
      this.message = message;
      this.replyTo = replyTo;
    }
  }
  public static class Pong {
    private String message;

    public Pong(String message) {
      this.message = message;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Pong)) return false;
      Pong pong = (Pong) o;
      return message.equals(pong.message);
    }

    @Override
    public int hashCode() {
      return Objects.hash(message);
    }
  }

  Behavior<Ping> echoActor = Behaviors.receive((context, ping) -> {
    ping.replyTo.tell(new Pong(ping.message));
    return Behaviors.same();
  });
  //#under-test

  //#test-shutdown
  @AfterClass
  public static void cleanup() {
    testKit.shutdownTestKit();
  }
  //#test-shutdown

  @Test
  public void testVerifyingAResponse() {
    //#test-spawn
    ActorRef<Ping> pinger = testKit.spawn(echoActor, "ping");
    TestProbe<Pong> probe = testKit.createTestProbe();
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    //#test-spawn
  }

  @Test
  public void testVerifyingAResponseAnonymous() {
    //#test-spawn-anonymous
    ActorRef<Ping> pinger = testKit.spawn(echoActor);
    //#test-spawn-anonymous
    TestProbe<Pong> probe = testKit.createTestProbe();
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
  }

  @Test
  public void testStoppingActors() {
    TestProbe<Pong> probe = testKit.createTestProbe();
    //#test-stop-actors
    ActorRef<Ping> pinger1 = testKit.spawn(echoActor, "pinger");
    pinger1.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    testKit.stop(pinger1);

    // Immediately creating an actor with the same name
    ActorRef<Ping> pinger2 = testKit.spawn(echoActor, "pinger");
    pinger2.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    testKit.stop(pinger2, Duration.ofSeconds(10));
    //#test-stop-actors
  }

  @Test
  public void systemNameShouldComeFromTestClass() {
    assertEquals(testKit.system().name(), "AsyncTestingExampleTest");
  }
}
