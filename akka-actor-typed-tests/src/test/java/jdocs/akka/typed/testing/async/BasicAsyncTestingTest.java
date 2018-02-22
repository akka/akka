/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package jdocs.akka.typed.testing.async;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.testkit.typed.javadsl.TestProbe;
import akka.testkit.typed.TestKit;
import org.junit.AfterClass;
import org.junit.Test;

//#test-header
public class BasicAsyncTestingTest extends TestKit {
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

  Behavior<Ping> echoActor = Behaviors.immutable((ctx, ping) -> {
    ping.replyTo.tell(new Pong(ping.msg));
    return Behaviors.same();
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
    //#test-spawn
    TestProbe<Pong> probe = TestProbe.create(system());
    ActorRef<Ping> pinger = spawn(echoActor, "ping");
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    //#test-spawn
  }

  @Test
  public void testVerifyingAResponseAnonymous() {
    //#test-spawn-anonymous
    TestProbe<Pong> probe = TestProbe.create(system());
    ActorRef<Ping> pinger = spawn(echoActor);
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    //#test-spawn-anonymous
  }
}
