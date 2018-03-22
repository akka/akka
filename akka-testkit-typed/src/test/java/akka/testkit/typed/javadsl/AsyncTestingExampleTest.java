/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.testkit.typed.javadsl.ActorTestKit;
import akka.testkit.typed.javadsl.TestProbe;
import org.junit.AfterClass;
import org.junit.Test;

//#test-header
public class AsyncTestingExampleTest {
  final static ActorTestKit testKit = ActorTestKit.create(AsyncTestingExampleTest.class);
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
    TestProbe<Pong> probe = testKit.createTestProbe();
    ActorRef<Ping> pinger = testKit.spawn(echoActor, "ping");
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    //#test-spawn
  }

  @Test
  public void testVerifyingAResponseAnonymous() {
    //#test-spawn-anonymous
    TestProbe<Pong> probe = testKit.createTestProbe();
    ActorRef<Ping> pinger = testKit.spawn(echoActor);
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
    //#test-spawn-anonymous
  }
}
