/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import com.typesafe.config.ConfigFactory
import akka.testkit._
import akka.dispatch._

object ActorMailboxSpec {
  val mailboxConf = ConfigFactory.parseString("""
    unbounded-dispatcher {
      mailbox-type = "akka.dispatch.UnboundedMailbox"
    }

    bounded-dispatcher {
      mailbox-capacity = 1000
      mailbox-push-timeout-time = 10s
      mailbox-type = "akka.dispatch.BoundedMailbox"
    }

    unbounded-mailbox {
      mailbox-type = "akka.dispatch.UnboundedMailbox"
    }

    bounded-mailbox {
      mailbox-capacity = 1000
      mailbox-push-timeout-time = 10s
      mailbox-type = "akka.dispatch.BoundedMailbox"
    }

    akka.actor.deployment {
      /default-default {
      }
      /default-override-from-props {
      }
      /default-override-from-trait {
      }
      /default-override-from-stash {
      }
      /default-bounded {
        mailbox = bounded-mailbox
      }
      /default-unbounded-deque {
        mailbox = akka.actor.mailbox.unbounded-deque-based
      }
      /default-unbounded-deque-override-trait {
        mailbox = akka.actor.mailbox.unbounded-deque-based
      }
      /unbounded-default {
        dispatcher = unbounded-dispatcher
      }
      /unbounded-default-override-trait {
        dispatcher = unbounded-dispatcher
      }
      /unbounded-bounded {
        dispatcher= unbounded-dispatcher
        mailbox = bounded-mailbox
      }
      /bounded-default {
        dispatcher = bounded-dispatcher
      }
      /bounded-unbounded {
        dispatcher = bounded-dispatcher
        mailbox = unbounded-mailbox
      }
      /bounded-unbounded-override-props {
        dispatcher = bounded-dispatcher
        mailbox = unbounded-mailbox
      }
    }

    akka.actor.mailbox.requirements {
      "akka.dispatch.BoundedMessageQueueSemantics" = bounded-mailbox
    }
  """)

  class QueueReportingActor extends Actor {
    def receive = {
      case _ ⇒ sender ! context.asInstanceOf[ActorCell].mailbox.messageQueue
    }
  }

  class BoundedQueueReportingActor extends QueueReportingActor with RequiresMessageQueue[BoundedMessageQueueSemantics]

  class StashQueueReportingActor extends QueueReportingActor with Stash

  val UnboundedMailboxTypes = Seq(classOf[QueueBasedMessageQueue], classOf[UnboundedMessageQueueSemantics])
  val BoundedMailboxTypes = Seq(classOf[QueueBasedMessageQueue], classOf[BoundedMessageQueueSemantics])
  val UnboundedDeqMailboxTypes = Seq(classOf[QueueBasedMessageQueue], classOf[DequeBasedMessageQueue],
    classOf[UnboundedDequeBasedMessageQueueSemantics])
}

class ActorMailboxSpec extends AkkaSpec(ActorMailboxSpec.mailboxConf) with DefaultTimeout with ImplicitSender {

  import ActorMailboxSpec._

  def checkMailboxQueue(props: Props, name: String, types: Seq[Class[_]]): Unit = {
    val actor = system.actorOf(props, name)

    actor ! "ping"
    val q = expectMsgType[MessageQueue]
    types foreach (t ⇒ assert(t isInstance q, s"Type [${q.getClass}] is not assignable to [${t}]"))
  }

  "An Actor" must {

    "get an unbounded message queue by default" in {
      checkMailboxQueue(Props[QueueReportingActor], "default-default", UnboundedMailboxTypes)
    }

    "get an unbounded dequeu message queue when it is only configured on the props" in {
      checkMailboxQueue(Props[QueueReportingActor].withMailbox("akka.actor.mailbox.unbounded-deque-based"),
        "default-override-from-props", UnboundedDeqMailboxTypes)
    }

    "get an bounded message queue when it's only configured with RequiresMailbox" in {
      checkMailboxQueue(Props[BoundedQueueReportingActor],
        "default-override-from-trait", BoundedMailboxTypes)
    }

    "get an unbounded dequeu message queue when it's only mixed with Stash" in {
      checkMailboxQueue(Props[StashQueueReportingActor],
        "default-override-from-stash", UnboundedDeqMailboxTypes)
    }

    "get a bounded message queue when it's configured as mailbox" in {
      checkMailboxQueue(Props[QueueReportingActor], "default-bounded", BoundedMailboxTypes)
    }

    "get an unbounded dequeu message queue when it's configured as mailbox" in {
      checkMailboxQueue(Props[QueueReportingActor], "default-unbounded-deque", UnboundedDeqMailboxTypes)
    }

    "get an unbounded dequeu message queue when it's configured as mailbox overriding RequestMailbox" in {
      filterEvents(EventFilter[IllegalArgumentException]()) {
        checkMailboxQueue(Props[BoundedQueueReportingActor], "default-unbounded-deque-override-trait",
          UnboundedDeqMailboxTypes)
      }
    }

    "get an unbounded message queue when defined in dispatcher" in {
      checkMailboxQueue(Props[QueueReportingActor], "unbounded-default", UnboundedMailboxTypes)
    }

    "get an unbounded message queue when defined in dispatcher overriding RequestMailbox" in {
      filterEvents(EventFilter[IllegalArgumentException]()) {
        checkMailboxQueue(Props[BoundedQueueReportingActor], "unbounded-default-override-trait", UnboundedMailboxTypes)
      }
    }

    "get a bounded message queue when it's configured as mailbox overriding unbounded in dispatcher" in {
      checkMailboxQueue(Props[QueueReportingActor], "unbounded-bounded", BoundedMailboxTypes)
    }

    "get a bounded message queue by when defined in dispatcher" in {
      checkMailboxQueue(Props[QueueReportingActor], "bounded-default", BoundedMailboxTypes)
    }

    "get an unbounded message queue when it's configured as mailbox overriding bounded in dispatcher" in {
      checkMailboxQueue(Props[QueueReportingActor], "bounded-unbounded", UnboundedMailboxTypes)
    }

    "get an unbounded message queue overriding configuration on the props" in {
      checkMailboxQueue(Props[QueueReportingActor].withMailbox("akka.actor.mailbox.unbounded-deque-based"),
        "bounded-unbounded-override-props", UnboundedMailboxTypes)
    }

  }
}
