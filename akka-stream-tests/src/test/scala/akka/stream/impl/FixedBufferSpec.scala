/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.testkit.AkkaSpec

class FixedBufferSpec extends AkkaSpec {

  for (size ← List(1, 3, 4)) {

    s"FixedSizeBuffer of size $size" must {

      "start as empty" in {
        val buf = FixedSizeBuffer(size)
        buf.isEmpty should be(true)
        buf.isFull should be(false)
      }

      "become nonempty after enqueueing" in {
        val buf = FixedSizeBuffer[String](size)
        buf.enqueue("test")
        buf.isEmpty should be(false)
        buf.isFull should be(size == 1)
      }

      "become full after size elements are enqueued" in {
        val buf = FixedSizeBuffer[String](size)
        for (_ ← 1 to size) buf.enqueue("test")
        buf.isEmpty should be(false)
        buf.isFull should be(true)
      }

      "become empty after enqueueing and tail drop" in {
        val buf = FixedSizeBuffer[String](size)
        buf.enqueue("test")
        buf.dropTail()
        buf.isEmpty should be(true)
        buf.isFull should be(false)
      }

      "become empty after enqueueing and head drop" in {
        val buf = FixedSizeBuffer[String](size)
        buf.enqueue("test")
        buf.dropHead()
        buf.isEmpty should be(true)
        buf.isFull should be(false)
      }

      "drop head properly" in {
        val buf = FixedSizeBuffer[Int](size)
        for (elem ← 1 to size) buf.enqueue(elem)
        buf.dropHead()
        for (elem ← 2 to size) buf.dequeue() should be(elem)
      }

      "drop tail properly" in {
        val buf = FixedSizeBuffer[Int](size)
        for (elem ← 1 to size) buf.enqueue(elem)
        buf.dropTail()
        for (elem ← 1 to size - 1) buf.dequeue() should be(elem)
      }

      "become non-full after tail dropped from full buffer" in {
        val buf = FixedSizeBuffer[String](size)
        for (_ ← 1 to size) buf.enqueue("test")
        buf.dropTail()
        buf.isEmpty should be(size == 1)
        buf.isFull should be(false)
      }

      "become non-full after head dropped from full buffer" in {
        val buf = FixedSizeBuffer[String](size)
        for (_ ← 1 to size) buf.enqueue("test")
        buf.dropTail()
        buf.isEmpty should be(size == 1)
        buf.isFull should be(false)
      }

      "work properly with full-range filling/draining cycles" in {
        val buf = FixedSizeBuffer[Int](size)

        for (_ ← 1 to 10) {
          buf.isEmpty should be(true)
          buf.isFull should be(false)
          for (elem ← 1 to size) buf.enqueue(elem)
          buf.isEmpty should be(false)
          buf.isFull should be(true)
          for (elem ← 1 to size) buf.dequeue() should be(elem)
        }

      }

    }
  }

}
