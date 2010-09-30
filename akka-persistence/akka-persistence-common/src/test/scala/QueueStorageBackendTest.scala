/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}
import scala.util.Random

/**
 * Implementation Compatibility test for PersistentQueue backend implementations.
 */

trait QueueStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: QueueStorageBackend[Array[Byte]]

  def dropQueues: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping queues")
    dropQueues
  }

  override def afterEach = {
    log.info("afterEach: dropping queues")
    dropQueues
  }



  describe("A Properly functioning QueueStorage Backend") {
    it("should enqueue properly when there is capacity in the queue") {
      val queue = "enqueueTest"
      val value = "enqueueTestValue".getBytes
      storage.size(queue) should be(0)
      storage.enqueue(queue, value).get should be(1)
      storage.size(queue) should be(1)
    }

    it("should return None when enqueue is called on a full queue?") {

    }

    it("should dequeue properly when the queue is empty") {
      val queue = "dequeueTest"
      val value = "dequeueTestValue".getBytes
      storage.size(queue) should be(0)
      storage.enqueue(queue, value)
      storage.size(queue) should be(1)
      storage.dequeue(queue).get should be(value)
    }

    it("should return None when dequeue is called on an empty queue") {
      val queue = "dequeueTest2"
      val value = "dequeueTestValue2".getBytes
      storage.size(queue) should be(0)
      storage.dequeue(queue) should be(None)
    }

    it("should accurately reflect the size of the queue") {
      val queue = "sizeTest"
      val rand = new Random(3).nextInt(100)
      val values = (1 to rand).toList.map {i: Int => ("sizeTestValue" + i).getBytes}
      values.foreach {storage.enqueue(queue, _)}
      storage.size(queue) should be(rand)
      val drand = new Random(3).nextInt(rand)
      (1 to drand).foreach {
        i: Int => {
          storage.dequeue(queue).isDefined should be(true)
          storage.size(queue) should be(rand - i)
        }
      }
    }

    it("should support peek properly") {
      val queue = "sizeTest"
      val rand = new Random(3).nextInt(100)
      val values = (1 to rand).toList.map {i: Int => ("peekTestValue" + i)}
      storage.remove(queue)
      values.foreach {s: String => storage.enqueue(queue, s.getBytes)}
      (1 to rand).foreach {
        index => {
          val peek = storage.peek(queue, 0, index).map {new String(_)}
          peek.size should be(index)
          values.dropRight(values.size - index).equals(peek) should be(true)
        }
      }
      (0 until rand).foreach {
        index => {
          val peek = storage.peek(queue, index, rand - index).map {new String(_)}
          peek.size should be(rand - index)
          values.drop(index).equals(peek) should be(true)
        }
      }

      //Should we test counts greater than queue size? or greater than queue size - count???
    }

    it("should not throw an exception when remove is called on a non-existent queue") {
      storage.remove("exceptionTest")
    }

    it("should remove queue storage properly") {
      val queue = "removeTest"
      val rand = new Random(3).nextInt(100)
      val values = (1 to rand).toList.map {i: Int => ("removeValue" + i).getBytes}
      values.foreach {storage.enqueue(queue, _)}
      storage.size(queue) should be(rand)
      storage.remove(queue)
      storage.size(queue) should be(0)
    }
  }

}