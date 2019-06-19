/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package jdocs.akka.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.MailboxSelector;
import akka.actor.typed.javadsl.Behaviors;

public class MailboxDocTest {

  static Behavior<String> childBehavior = null;

  static Behavior<Void> parentBehavior = Behaviors.setup(context -> {

    // #select-mailbox
    context.spawn(
        childBehavior,
        "bounded-mailbox-child",
        MailboxSelector.bounded(100));

    context.spawn(
        childBehavior,
        "from-config-mailbox-child",
        MailboxSelector.fromConfig("absolute.config.path")
    );
    // #select-mailbox

    return Behaviors.empty();
  });

}
