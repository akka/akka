/*
 * Copyright (C) 2022-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.testkit.StreamSpec

class PartitionedBufferSpec extends StreamSpec {
  val doNothing: Any => Unit = _ => ()

  val linearCapacity = scala.util.Random.nextInt(32) + 1
  val capacityPerPartition = scala.util.Random.nextInt((linearCapacity / 2).max(1)) + 1
  val numPartitions = {
    val q = linearCapacity / capacityPerPartition
    if (q * capacityPerPartition == linearCapacity) q else q + 1
  }

  def makePartitionBuffer[T](hardCapacity: Int, callback: T => Unit): Buffer[T] =
    if (capacityPerPartition < hardCapacity) {
      val headBuffer = FixedSizeBuffer[T](capacityPerPartition)
      val tailBuffer = new BoundedBuffer.DynamicQueue[T](hardCapacity - capacityPerPartition)

      new ChainedBuffer(headBuffer, tailBuffer, callback)
    } else {
      FixedSizeBuffer[T](hardCapacity)
    }

  def buffer(callback: Int => Unit = doNothing): PartitionedBuffer[Int, Int] =
    new PartitionedBuffer(linearBuffer = Buffer(linearCapacity, 32), partitioner = { x =>
      (x % numPartitions).abs
    }, makePartitionBuffer = { (k, c) =>
      callback(k); makePartitionBuffer(c, doNothing)
    })

  s"PartitionedBuffer of capacity $linearCapacity and per-partition capacity $capacityPerPartition" must {
    s"have capacity $linearCapacity" in {
      val partitionBuffer = buffer()

      partitionBuffer.capacity shouldBe linearCapacity
    }

    "partition enqueued elements" in {
      val partitionBuffer = buffer()

      val elem = scala.util.Random.nextInt()
      partitionBuffer.enqueue(elem)

      partitionBuffer.peekPartition(partitionBuffer.partitioner(elem)) should contain(elem)
    }

    "support dropHead'ing from partition sub-buffers" in {
      val partitionBuffer = buffer()

      val elem = scala.util.Random.nextInt()
      partitionBuffer.enqueue(elem)
      val key = partitionBuffer.partitioner(elem)
      val enqueueSecond = linearCapacity > 1
      val nextElem = {
        val x = elem + numPartitions
        if (x > elem) x else x - numPartitions
      }

      if (enqueueSecond) {
        partitionBuffer.enqueue(nextElem)
      }

      partitionBuffer.dropOnlyPartitionHead(key)

      partitionBuffer.dequeue() shouldBe elem
      partitionBuffer.peekPartition(key) should be(if (enqueueSecond) Some(nextElem) else None)
    }

    "dequeue from partition sub-buffers when dequeueing" in {
      val partitionBuffer = buffer()

      val elem = scala.util.Random.nextInt()
      partitionBuffer.enqueue(elem)
      val key = partitionBuffer.partitioner(elem)
      val enqueueSecond = linearCapacity > 1 && scala.util.Random.nextBoolean()
      val nextElem = {
        val x = elem + numPartitions
        if (x > elem) x else x - numPartitions
      }

      if (enqueueSecond) {
        partitionBuffer.enqueue(nextElem)
      }

      partitionBuffer.dequeue() shouldBe elem
      partitionBuffer.peekPartition(key) shouldNot contain(elem)
    }

    "clear sub-buffers when clearing" in {
      val partitionBuffer = buffer()

      val elem = scala.util.Random.nextInt()
      partitionBuffer.enqueue(elem)
      val key = partitionBuffer.partitioner(elem)

      partitionBuffer.clear()

      partitionBuffer.isEmpty shouldBe true
      partitionBuffer.peekPartition(key) shouldBe empty
    }

    "not support dropTail" in {
      val partitionBuffer = buffer()

      an[UnsupportedOperationException] shouldBe thrownBy {
        partitionBuffer.dropTail()
      }
    }
  }
}
