/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.actor.ActorCell
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.internal.adapter.ActorContextAdapter
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.dispatch.BoundedMessageQueueSemantics
import akka.dispatch.BoundedNodeMessageQueue
import akka.dispatch.MessageQueue
import akka.dispatch.UnboundedMessageQueueSemantics
import org.scalatest.WordSpecLike

class MailboxSelectorSpec extends ScalaTestWithActorTestKit("""
    specific-mailbox {
      mailbox-type = "akka.dispatch.NonBlockingBoundedMailbox"
      mailbox-capacity = 4 
    }
  """) with WordSpecLike with LogCapturing {

  case class WhatsYourMailbox(replyTo: ActorRef[MessageQueue])
  private def behavior: Behavior[WhatsYourMailbox] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage[WhatsYourMailbox] {
        case WhatsYourMailbox(replyTo) =>
          val mailbox = context match {
            case adapter: ActorContextAdapter[_] =>
              adapter.classicContext match {
                case cell: ActorCell =>
                  cell.mailbox.messageQueue
              }
          }

          replyTo ! mailbox
          Behaviors.stopped
      }
    }

  "The Mailbox selectors" must {
    "default to unbounded" in {
      val actor = spawn(behavior)
      val mailbox = actor.ask(WhatsYourMailbox).futureValue
      mailbox shouldBe a[UnboundedMessageQueueSemantics]
    }

    "select a bounded mailbox" in {
      val actor = spawn(behavior, MailboxSelector.bounded(3))
      val mailbox = actor.ask(WhatsYourMailbox).futureValue
      mailbox shouldBe a[BoundedMessageQueueSemantics]
      // capacity is private so only way to test is to fill mailbox
    }

    "set capacity on a bounded mailbox" in {
      val latch = new CountDownLatch(1)
      val actor = spawn(Behaviors.receiveMessage[String] {
        case "one" =>
          // block here so we can fill mailbox up
          latch.await(10, TimeUnit.SECONDS)
          Behaviors.same
        case _ =>
          Behaviors.same
      }, MailboxSelector.bounded(2))
      actor ! "one" // actor will block here
      actor ! "two"
      LoggingTestKit.deadLetters().intercept {
        // one or both of these doesn't fit in mailbox
        // depending on race with how fast actor consumes
        actor ! "three"
        actor ! "four"
      }
      latch.countDown()
    }

    "select an arbitrary mailbox from config" in {
      val actor = spawn(behavior, MailboxSelector.fromConfig("specific-mailbox"))
      val mailbox = actor.ask(WhatsYourMailbox).futureValue
      mailbox shouldBe a[BoundedMessageQueueSemantics]
      mailbox.asInstanceOf[BoundedNodeMessageQueue].capacity should ===(4)

    }
  }

}
