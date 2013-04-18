/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.actor;

import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Inbox;
import akka.actor.PoisonPill;
import akka.actor.Terminated;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

public class InboxDocTest {

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("MySystem", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }
  
  @Test
  public void demonstrateInbox() {
    final JavaTestKit probe = new JavaTestKit(system);
    final ActorRef target = probe.getRef();
    //#inbox
    final Inbox inbox = Inbox.create(system);
    inbox.send(target, "hello");
    //#inbox
    probe.expectMsgEquals("hello");
    probe.send(probe.getLastSender(), "world");
    //#inbox
    assert inbox.receive(Duration.create(1, TimeUnit.SECONDS)).equals("world");
    //#inbox
  }
  
  @Test
  public void demonstrateWatch() {
    final JavaTestKit probe = new JavaTestKit(system);
    final ActorRef target = probe.getRef();
    //#watch
    final Inbox inbox = Inbox.create(system);
    inbox.watch(target);
    target.tell(PoisonPill.getInstance(), null);
    assert inbox.receive(Duration.create(1, TimeUnit.SECONDS)) instanceof Terminated;
    //#watch
  }

}
