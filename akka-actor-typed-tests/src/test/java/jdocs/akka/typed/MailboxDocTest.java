/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import akka.Done;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.MailboxSelector;
import akka.actor.typed.javadsl.Behaviors;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;

public class MailboxDocTest {

  @ClassRule
  public static final TestKitJunitResource testKit =
      new TestKitJunitResource(ConfigFactory.load("mailbox-config-sample.conf"));

  @Test
  public void startSomeActorsWithDifferentMailboxes() {
    TestProbe<Done> testProbe = testKit.createTestProbe();
    Behavior<String> childBehavior = Behaviors.empty();

    Behavior<Void> setup =
        Behaviors.setup(
            context -> {
              // #select-mailbox
              context.spawn(childBehavior, "bounded-mailbox-child", MailboxSelector.bounded(100));

              context.spawn(
                  childBehavior,
                  "from-config-mailbox-child",
                  MailboxSelector.fromConfig("my-app.my-special-mailbox"));
              // #select-mailbox

              testProbe.ref().tell(Done.getInstance());
              return Behaviors.stopped();
            });

    ActorRef<Void> ref = testKit.spawn(setup);
    testProbe.receiveMessage();
  }
}
