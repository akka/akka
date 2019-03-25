/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.testkit.AkkaJUnitActorSystemResource;
import jdocs.AbstractJavaTest;
import akka.testkit.javadsl.TestKit;
import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Inbox;
import akka.actor.PoisonPill;
import akka.actor.Terminated;
import akka.testkit.AkkaSpec;

import java.time.Duration;

public class InboxDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("InboxDocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void demonstrateInbox() {
    final TestKit probe = new TestKit(system);
    final ActorRef target = probe.getRef();
    // #inbox
    final Inbox inbox = Inbox.create(system);
    inbox.send(target, "hello");
    // #inbox
    probe.expectMsgEquals("hello");
    probe.send(probe.getLastSender(), "world");
    // #inbox
    try {
      assert inbox.receive(Duration.ofSeconds(1)).equals("world");
    } catch (java.util.concurrent.TimeoutException e) {
      // timeout
    }
    // #inbox
  }

  @Test
  public void demonstrateWatch() {
    final TestKit probe = new TestKit(system);
    final ActorRef target = probe.getRef();
    // #watch
    final Inbox inbox = Inbox.create(system);
    inbox.watch(target);
    target.tell(PoisonPill.getInstance(), ActorRef.noSender());
    try {
      assert inbox.receive(Duration.ofSeconds(1)) instanceof Terminated;
    } catch (java.util.concurrent.TimeoutException e) {
      // timeout
    }
    // #watch
  }
}
