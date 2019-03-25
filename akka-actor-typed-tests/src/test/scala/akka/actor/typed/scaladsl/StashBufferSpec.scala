/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.typed.Behavior
import akka.actor.testkit.typed.internal.StubbedActorContext
import akka.actor.testkit.typed.scaladsl.TestInbox
import org.scalatest.{ Matchers, WordSpec }

class StashBufferSpec extends WordSpec with Matchers {

  val context = new StubbedActorContext[String]("StashBufferSpec")

  "A StashBuffer" must {

    "answer empty correctly" in {
      val buffer = StashBuffer[String](10)
      buffer.isEmpty should ===(true)
      buffer.nonEmpty should ===(false)
      buffer.stash("m1")
      buffer.isEmpty should ===(false)
      buffer.nonEmpty should ===(true)
    }

    "append and drop" in {
      val buffer = StashBuffer[String](10)
      buffer.size should ===(0)
      buffer.stash("m1")
      buffer.size should ===(1)
      buffer.stash("m2")
      buffer.size should ===(2)
      val m1 = buffer.head
      m1 should ===("m1")
      buffer.size should ===(2)
      buffer.unstash(context, Behaviors.ignore, 1, identity)
      buffer.size should ===(1)
      m1 should ===("m1")
      val m2 = buffer.head
      m2 should ===("m2")
      buffer.unstash(context, Behaviors.ignore, 1, identity)
      buffer.size should ===(0)
      intercept[NoSuchElementException] {
        buffer.head
      }
      buffer.size should ===(0)
    }

    "enforce capacity" in {
      val buffer = StashBuffer[String](3)
      buffer.stash("m1")
      buffer.stash("m2")
      buffer.stash("m3")
      intercept[StashOverflowException] {
        buffer.stash("m4")
      }
      // it's actually a javadsl.StashOverflowException
      intercept[akka.actor.typed.javadsl.StashOverflowException] {
        buffer.stash("m4")
      }
      buffer.size should ===(3)
    }

    "process elements in the right order" in {
      val buffer = StashBuffer[String](10)
      buffer.stash("m1")
      buffer.stash("m2")
      buffer.stash("m3")
      val sb1 = new StringBuilder()
      buffer.foreach(sb1.append(_))
      sb1.toString() should ===("m1m2m3")
      buffer.unstash(context, Behaviors.ignore, 1, identity)
      val sb2 = new StringBuilder()
      buffer.foreach(sb2.append(_))
      sb2.toString() should ===("m2m3")
    }

    "unstash to returned behaviors" in {
      val buffer = StashBuffer[String](10)
      buffer.stash("m1")
      buffer.stash("m2")
      buffer.stash("m3")
      buffer.stash("get")

      val valueInbox = TestInbox[String]()
      def behavior(state: String): Behavior[String] =
        Behaviors.receive[String] { (_, message) =>
          if (message == "get") {
            valueInbox.ref ! state
            Behaviors.same
          } else {
            behavior(state + message)
          }
        }

      buffer.unstashAll(context, behavior(""))
      valueInbox.expectMessage("m1m2m3")
      buffer.isEmpty should ===(true)
    }

    "undefer returned behaviors when unstashing" in {
      val buffer = StashBuffer[String](10)
      buffer.stash("m1")
      buffer.stash("m2")
      buffer.stash("m3")
      buffer.stash("get")

      val valueInbox = TestInbox[String]()
      def behavior(state: String): Behavior[String] =
        Behaviors.receive[String] { (_, message) =>
          if (message == "get") {
            valueInbox.ref ! state
            Behaviors.same
          } else {
            Behaviors.setup[String](_ => behavior(state + message))
          }
        }

      buffer.unstashAll(context, behavior(""))
      valueInbox.expectMessage("m1m2m3")
      buffer.isEmpty should ===(true)
    }

    "be able to stash while unstashing" in {
      val buffer = StashBuffer[String](10)
      buffer.stash("m1")
      buffer.stash("m2")
      buffer.stash("m3")
      buffer.stash("get")

      val valueInbox = TestInbox[String]()
      def behavior(state: String): Behavior[String] =
        Behaviors.receive[String] { (_, message) =>
          if (message == "get") {
            valueInbox.ref ! state
            Behaviors.same
          } else if (message == "m2") {
            buffer.stash("m2")
            Behaviors.same
          } else {
            behavior(state + message)
          }
        }

      // It's only supposed to unstash the messages that are in the buffer when
      // the call is made, not unstash new messages added to the buffer while
      // unstashing.
      val b2 = buffer.unstashAll(context, behavior(""))
      valueInbox.expectMessage("m1m3")
      buffer.size should ===(1)
      buffer.head should ===("m2")

      buffer.unstashAll(context, b2)
      buffer.size should ===(1)
      buffer.head should ===("m2")
    }

  }

}
