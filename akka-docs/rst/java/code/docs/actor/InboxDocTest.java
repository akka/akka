/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actor;

import java.util.concurrent.TimeUnit;

import akka.testkit.AkkaJUnitActorSystemResource;
import docs.AbstractJavaTest;
import org.junit.ClassRule;
import org.junit.Test;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Inbox;
import akka.actor.PoisonPill;
import akka.actor.Terminated;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

public class InboxDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("InboxDocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

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
    try {
      assert inbox.receive(Duration.create(1, TimeUnit.SECONDS)).equals("world");
    } catch (java.util.concurrent.TimeoutException e) {
      // timeout
    }
    //#inbox
  }
  
  @Test
  public void demonstrateWatch() {
    final JavaTestKit probe = new JavaTestKit(system);
    final ActorRef target = probe.getRef();
    //#watch
    final Inbox inbox = Inbox.create(system);
    inbox.watch(target);
    target.tell(PoisonPill.getInstance(), ActorRef.noSender());
    try {
      assert inbox.receive(Duration.create(1, TimeUnit.SECONDS)) instanceof Terminated;
    } catch (java.util.concurrent.TimeoutException e) {
      // timeout
    }
    //#watch
  }

}
