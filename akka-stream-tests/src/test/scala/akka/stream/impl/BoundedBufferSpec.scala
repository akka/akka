/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import scala.annotation.nowarn

import akka.stream.testkit.StreamSpec

@nowarn("msg=deprecated")
class BoundedBufferSpec extends StreamSpec {

  for (size <- List(1, 3, 4)) {

    s"BoundedBuffer of size $size" must {

      "start as empty" in {
        val buf = new BoundedBuffer(size)
        buf.isEmpty should be(true)
        buf.isFull should be(false)
      }

      "become nonempty after enqueueing" in {
        val buf = new BoundedBuffer[String](size)
        buf.enqueue("test")
        buf.isEmpty should be(false)
        buf.isFull should be(size == 1)
      }

      "become full after size elements are enqueued" in {
        val buf = new BoundedBuffer[String](size)
        for (_ <- 1 to size) buf.enqueue("test")
        buf.isEmpty should be(false)
        buf.isFull should be(true)
      }

      "become empty after enqueueing and tail drop" in {
        val buf = new BoundedBuffer[String](size)
        buf.enqueue("test")
        buf.dropTail()
        buf.isEmpty should be(true)
        buf.isFull should be(false)
      }

      "become empty after enqueueing and head drop" in {
        val buf = new BoundedBuffer[String](size)
        buf.enqueue("test")
        buf.dropHead()
        buf.isEmpty should be(true)
        buf.isFull should be(false)
      }

      "drop head properly" in {
        val buf = new BoundedBuffer[Int](size)
        for (elem <- 1 to size) buf.enqueue(elem)
        buf.dropHead()
        for (elem <- 2 to size) buf.dequeue() should be(elem)
      }

      "drop tail properly" in {
        val buf = new BoundedBuffer[Int](size)
        for (elem <- 1 to size) buf.enqueue(elem)
        buf.dropTail()
        for (elem <- 1 to size - 1) buf.dequeue() should be(elem)
      }

      "become non-full after tail dropped from full buffer" in {
        val buf = new BoundedBuffer[String](size)
        for (_ <- 1 to size) buf.enqueue("test")
        buf.dropTail()
        buf.isEmpty should be(size == 1)
        buf.isFull should be(false)
      }

      "become non-full after head dropped from full buffer" in {
        val buf = new BoundedBuffer[String](size)
        for (_ <- 1 to size) buf.enqueue("test")
        buf.dropHead()
        buf.isEmpty should be(size == 1)
        buf.isFull should be(false)
      }

      "peek shows head of queue" in {
        val buf = new BoundedBuffer[Int](size)
        for (n <- 1 to size) {
          buf.enqueue(n)
          buf.peek() should ===(1)
        }
      }

      "work properly with full-range filling/draining cycles" in {
        val buf = new BoundedBuffer[Int](size)

        for (_ <- 1 to 10) {
          buf.isEmpty should be(true)
          buf.isFull should be(false)
          for (elem <- 1 to size) buf.enqueue(elem)
          buf.isEmpty should be(false)
          buf.isFull should be(true)
          for (elem <- 1 to size) buf.dequeue() should be(elem)
        }
      }

      "work when indexes wrap around at Int.MaxValue" in {
        import language.reflectiveCalls
        val buf = new BoundedBuffer[Int](size)

        try {
          val cheat = buf.asInstanceOf[{ def readIdx_=(l: Long): Unit; def writeIdx_=(l: Long): Unit }]
          cheat.readIdx_=(Int.MaxValue)
          cheat.writeIdx_=(Int.MaxValue)

          for (_ <- 1 to 10) {
            buf.isEmpty should be(true)
            buf.isFull should be(false)
            for (elem <- 1 to size) buf.enqueue(elem)
            buf.isEmpty should be(false)
            buf.isFull should be(true)
            for (elem <- 1 to size) buf.dequeue() should be(elem)
          }
        } catch {
          case _: NoSuchMethodException =>
          // our 'cheat' doesn't work on Scala 3
        }
      }

    }
  }

}
