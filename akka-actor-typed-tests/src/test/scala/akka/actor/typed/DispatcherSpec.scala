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

  case class WhatsYourDispatcherAndMailbox(replyTo: ActorRef[(MessageDispatcher, MessageQueue)])

  private def behavior: Behavior[WhatsYourDispatcherAndMailbox] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage[WhatsYourDispatcherAndMailbox] {
        case WhatsYourDispatcherAndMailbox(replyTo) =>
          val result = context match {
            case adapter: ActorContextAdapter[_] =>
              adapter.classicActorContext match {
                case cell: ActorCell =>
                  (cell.dispatcher, cell.mailbox.messageQueue)
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
      val (dispatcher, mailbox) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      dispatcher.id eq Dispatchers.DefaultDispatcherId
      mailbox shouldBe a[UnboundedMessageQueueSemantics]
    }

    "select a blocking dispatcher" in {
      val actor = spawn(behavior, DispatcherSelector.blocking())
      val (dispatcher, _) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      dispatcher.id eq Dispatchers.DefaultBlockingDispatcherId
    }

    "select an specific dispatcher from config with mailbox selector" in {
      val actor =
        spawn(behavior, DispatcherSelector.fromConfig("specific-dispatcher").withMailboxFromConfig("specific-mailbox"))
      val (dispatcher, mailbox) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      mailbox shouldBe a[BoundedMessageQueueSemantics]
      mailbox.asInstanceOf[BoundedNodeMessageQueue].capacity should ===(4)
      dispatcher.id eq "specific-dispatcher"
    }

    "select an specific dispatcher from config with mailbox config" in {
      val actor = spawn(behavior, DispatcherSelector.fromConfig("specific-dispatcher-with-mailbox"))
      val (dispatcher, mailbox) = actor.ask(WhatsYourDispatcherAndMailbox.apply).futureValue
      mailbox shouldBe a[BoundedMessageQueueSemantics]
      mailbox.asInstanceOf[BoundedNodeMessageQueue].capacity should ===(4)
      dispatcher.id eq "specific-dispatcher-with-mailbox"
    }

  }

}
