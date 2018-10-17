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

import static org.junit.Assert.assertEquals;

//#test-header
public class AsyncTestingExampleTest {
  final static ActorTestKit testKit = ActorTestKit.create();
//#test-header

  //#under-test
  public static class Ping {
    private String msg;
    private ActorRef<Pong> replyTo;

    public Ping(String msg, ActorRef<Pong> replyTo) {
      this.msg = msg;
      this.replyTo = replyTo;
    }
  }
  public static class Pong {
    private String msg;

    public Pong(String msg) {
      this.msg = msg;
    }
  }

  Behavior<Ping> echoActor = Behaviors.receive((ctx, ping) -> {
    ping.replyTo.tell(new Pong(ping.msg));
    return Behaviors.same();
  });
  //#under-test

  //#test-shutdown
  @AfterClass
  public void cleanup() {
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
  public void systemNameShouldComeFromTestClass() {
    assertEquals(testKit.system().name(), "AsyncTestingExampleTest");
  }
}
