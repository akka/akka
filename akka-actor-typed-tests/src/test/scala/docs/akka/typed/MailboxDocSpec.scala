/**
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import akka.actor.typed.Behavior
import akka.actor.typed.MailboxSelector
import akka.actor.typed.scaladsl.Behaviors

object MailboxDocSpec {

  def childBehavior: Behavior[String] = ???

  val parent = Behaviors.setup[Nothing] { context =>
    // #select-mailbox
    context.spawn(
      childBehavior,
      "bounded-mailbox-child",
      MailboxSelector.bounded(100))

    context.spawn(
      childBehavior,
      "from-config-mailbox-child",
      MailboxSelector.fromConfig("absolute.config.path")
    )
    // #select-mailbox

    Behaviors.empty
  }
}
