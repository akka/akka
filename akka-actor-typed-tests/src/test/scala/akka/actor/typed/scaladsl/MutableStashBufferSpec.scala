/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import org.scalatest.Matchers
import org.scalatest.WordSpec

class MutableStashBufferSpec extends WordSpec with Matchers {

  "A MutableStashBuffer" must {

    "answer empty correctly" in {
      val buffer = MutableStashBuffer[String](10)
      buffer.isEmpty should ===(true)
      buffer.nonEmpty should ===(false)
      buffer.stash("m1")
      buffer.isEmpty should ===(false)
      buffer.nonEmpty should ===(true)
    }

    "append and drop" in {
      val buffer = MutableStashBuffer[String](10)
      buffer.size should ===(0)
      buffer.stash("m1")
      buffer.size should ===(1)
      buffer.stash("m2")
      buffer.size should ===(2)
      val m1 = buffer.head
      m1 should ===("m1")
      buffer.size should ===(2)
      buffer.dropHead()
      buffer.size should ===(1)
      m1 should ===("m1")
      val m2 = buffer.head
      m2 should ===("m2")
      buffer.dropHead()
      buffer.size should ===(0)
      intercept[NoSuchElementException] {
        buffer.dropHead()
      }
      intercept[NoSuchElementException] {
        buffer.head
      }
      buffer.size should ===(0)
    }

    "enforce capacity" in {
      val buffer = MutableStashBuffer[String](3)
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
      val buffer = MutableStashBuffer[String](10)
      buffer.stash("m1")
      buffer.stash("m2")
      buffer.stash("m3")
      val sb1 = new StringBuilder()
      buffer.foreach(sb1.append(_))
      sb1.toString() should ===("m1m2m3")
      buffer.dropHead()
      val sb2 = new StringBuilder()
      buffer.foreach(sb2.append(_))
      sb2.toString() should ===("m2m3")
    }

  }

}

