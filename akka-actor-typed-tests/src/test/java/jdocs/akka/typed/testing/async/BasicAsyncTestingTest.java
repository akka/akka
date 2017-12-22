/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed.testing.async;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Actor;
import akka.testkit.typed.javadsl.TestProbe;
import akka.testkit.typed.TestKit;
import org.junit.AfterClass;
import org.junit.Test;

//#test-header
public class BasicAsyncTestingTest extends TestKit {
  public BasicAsyncTestingTest() {
    super("BasicAsyncTestingTest");
  }
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

  Behavior<Ping> echoActor = Actor.immutable((ctx, ping) -> {
    ping.replyTo.tell(new Pong(ping.msg));
    return Actor.same();
  });
  //#under-test

  //#test-shutdown
  @AfterClass
  public void cleanup() {
    this.shutdown();
  }
  //#test-shutdown

  @Test
  public void testVerifyingAResponse() {
    //#test
    TestProbe<Pong> probe = new TestProbe<>(system(), testkitSettings());
    ActorRef<Ping> pinger = systemActor(echoActor, "ping");
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMsg(new Pong("hello"));
    //#test
  }
}
