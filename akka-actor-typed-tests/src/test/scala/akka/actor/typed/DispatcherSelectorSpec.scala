/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.ActorCell
import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.internal.adapter.ActorContextAdapter
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.dispatch._
import org.scalatest.wordspec.AnyWordSpecLike

class DispatcherSelectorSpec extends ScalaTestWithActorTestKit("""
    specific-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
    }
    specific-mailbox {
      mailbox-type = "akka.dispatch.NonBlockingBoundedMailbox"
      mailbox-capacity = 4
    }
    specific-dispatcher-with-mailbox {
      type = Dispatcher
      executor = "thread-pool-executor"
      mailbox-type = "akka.dispatch.NonBlockingBoundedMailbox"
      mailbox-capacity = 4
    }
  """) with AnyWordSpecLike with LogCapturing {

  case class WhatsYourDispatcherAndMailbox(replyTo: ActorRef[(String, MessageQueue)])

  private def behavior: Behavior[WhatsYourDispatcherAndMailbox] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage[WhatsYourDispatcherAndMailbox] {
        case WhatsYourDispatcherAndMailbox(replyTo) =>
          val result = context match {
            case adapter: ActorContextAdapter[_] =>
              adapter.classicActorContext match {
                case cell: ActorCell =>
                  (cell.dispatcher.id, cell.mailbox.messageQueue)
                case unexpected => throw new RuntimeException(s"Unexpected: $unexpected")
              }
            case unexpected => throw new RuntimeException(s"Unexpected: $unexpected")
          }

          replyTo ! result
          Behaviors.stopped
      }
    }

  "The Dispatcher selectors" must {

    "default to unbounded" in {
      val actor = spawn(behavior)
      val (_, mailbox) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      mailbox shouldBe a[UnboundedMessageQueueSemantics]
    }

    "select a blocking dispatcher" in {
      val actor = spawn(behavior, DispatcherSelector.blocking())
      val (dispatcherId, _) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      dispatcherId eq Dispatchers.DefaultBlockingDispatcherId
    }

    "select an specific dispatcher from config with mailbox selector" in {
      val actor =
        spawn(behavior, DispatcherSelector.fromConfig("specific-dispatcher").withMailboxFromConfig("specific-mailbox"))
      val (dispatcherId, mailbox) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      mailbox shouldBe a[BoundedMessageQueueSemantics]
      mailbox.asInstanceOf[BoundedNodeMessageQueue].capacity should ===(4)
      dispatcherId eq "specific-dispatcher"
    }

    "select an mailbox via dispatcher config would not work" in {
      val actor = spawn(behavior, DispatcherSelector.fromConfig("specific-dispatcher-with-mailbox"))
      val (dispatcherId, mailbox) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      mailbox shouldBe a[NodeMessageQueue]
      dispatcherId eq "specific-dispatcher-with-mailbox"
    }

  }

}
