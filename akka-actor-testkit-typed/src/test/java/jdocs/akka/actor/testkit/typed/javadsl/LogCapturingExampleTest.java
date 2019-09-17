/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.actor.testkit.typed.javadsl;

import static jdocs.akka.actor.testkit.typed.javadsl.AsyncTestingExampleTest.*;

// #log-capturing
import akka.actor.testkit.typed.javadsl.LogCapturing;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class LogCapturingExampleTest {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  @Test
  public void testSomething() {
    ActorRef<Ping> pinger = testKit.spawn(echoActor(), "ping");
    TestProbe<Pong> probe = testKit.createTestProbe();
    pinger.tell(new Ping("hello", probe.ref()));
    probe.expectMessage(new Pong("hello"));
  }
}
// #log-capturing
