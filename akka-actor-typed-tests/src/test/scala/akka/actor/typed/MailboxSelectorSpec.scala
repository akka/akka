/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

class MailboxSelectorSpec extends ScalaTestWithActorTestKit("""
    specific-mailbox {
      mailbox-type = "akka.dispatch.NonBlockingBoundedMailbox"
      mailbox-capacity = 4 
    }
  """) with WordSpecLike {

  "The Mailbox selectors" must {
    "default to unbounded" in {
      spawn(Behaviors.empty[String])
      // FIXME how to verify?
      fail()
    }

    "select a bounded mailbox" in {
      spawn(Behaviors.empty[String], MailboxSelector.bounded(3))
      // FIXME how to verify?
      fail()
    }

    "select an arbitrary mailbox from config" in {
      spawn(Behaviors.empty[String], MailboxSelector.fromConfig("specific-mailbox"))
      // FIXME how to verify?
      fail()
    }
  }

}
