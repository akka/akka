/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor.mailbox

import akka.actor.mailbox.DurableMailboxSpec

object MyDurableMailboxDocSpec {
  val config = """
    MyStorage-dispatcher {
      mailbox-type = docs.actor.mailbox.MyDurableMailboxType
    }
    """
}

class MyDurableMailboxDocSpec extends DurableMailboxSpec("MyStorage", MyDurableMailboxDocSpec.config) {
  override def atStartup() {
  }

  override def afterTermination() {
  }

  "MyDurableMailbox (Java)" must {
    "deliver a message" in {
      val actor = createMailboxTestActor()
      implicit val sender = testActor
      actor ! "hello"
      expectMsg("hello")
    }
  }
}