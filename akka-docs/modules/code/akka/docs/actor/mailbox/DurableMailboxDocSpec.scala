/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor.mailbox

//#imports
import akka.actor.Props

//#imports

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec
import akka.actor.Actor

class MyActor extends Actor {
  def receive = {
    case x ⇒
  }
}

object DurableMailboxDocSpec {
  val config = """
    //#dispatcher-config
    my-dispatcher {
      mailboxType = akka.actor.mailbox.FileBasedMailboxType
    }
    //#dispatcher-config
    """
}

class DurableMailboxDocSpec extends AkkaSpec(DurableMailboxDocSpec.config) {

  "configuration of dispatcher with durable mailbox" in {
    //#dispatcher-config-use
    val myActor = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), name = "myactor")
    //#dispatcher-config-use
  }

}
