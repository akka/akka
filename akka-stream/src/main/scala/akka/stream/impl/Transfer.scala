/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.spi.Subscription
import java.util.Arrays

trait Inputs {
  def NeedsInput: TransferState
  def NeedsInputOrComplete: TransferState

  def enqueueInputElement(elem: Any): Unit
  def dequeueInputElement(): Any

  def cancel(): Unit
  def complete(): Unit
  def isClosed: Boolean
  def isOpen: Boolean = !isClosed

  def prefetch(): Unit
  def clear(): Unit

  def inputsDepleted: Boolean
  def inputsAvailable: Boolean
}

trait Outputs {
  def NeedsDemand: TransferState
  def NeedsDemandOrCancel: TransferState

  def demandAvailable: Boolean
  def enqueueOutputElement(elem: Any): Unit

  def complete(): Unit
  def cancel(): Unit
  def isClosed: Boolean
  def isOpen: Boolean = !isClosed
}

// States of the operation that is executed by this processor
trait TransferState {
  def isReady: Boolean
  def isCompleted: Boolean
  def isExecutable = isReady && !isCompleted

  def ||(other: TransferState): TransferState = new TransferState {
    def isReady: Boolean = TransferState.this.isReady || other.isReady
    def isCompleted: Boolean = TransferState.this.isCompleted && other.isCompleted
  }

  def &&(other: TransferState): TransferState = new TransferState {
    def isReady: Boolean = TransferState.this.isReady && other.isReady
    def isCompleted: Boolean = TransferState.this.isCompleted || other.isCompleted
  }
}

object Completed extends TransferState {
  def isReady = false
  def isCompleted = true
}

object NotInitialized extends TransferState {
  def isReady = false
  def isCompleted = false
}

object EmptyInputs extends Inputs {
  override def inputsAvailable: Boolean = false
  override def inputsDepleted: Boolean = true
  override def isClosed: Boolean = true

  override def complete(): Unit = ()
  override def cancel(): Unit = ()
  override def prefetch(): Unit = ()
  override def clear(): Unit = ()

  override def dequeueInputElement(): Any = throw new UnsupportedOperationException("Cannot dequeue from EmptyInputs")
  override def enqueueInputElement(elem: Any): Unit = throw new UnsupportedOperationException("Cannot enqueue to EmptyInputs")

  override val NeedsInputOrComplete: TransferState = new TransferState {
    override def isReady: Boolean = true
    override def isCompleted: Boolean = false
  }
  override val NeedsInput: TransferState = Completed
}

class BatchingInputBuffer(val upstream: Subscription, val size: Int) extends Inputs {
  // TODO: buffer and batch sizing heuristics
  private var inputBuffer = Array.ofDim[AnyRef](size)
  private var inputBufferElements = 0
  private var nextInputElementCursor = 0
  private var upstreamCompleted = false
  private val IndexMask = size - 1

  private def requestBatchSize = math.max(1, inputBuffer.length / 2)
  private var batchRemaining = requestBatchSize

  override def prefetch(): Unit = upstream.requestMore(inputBuffer.length)

  override def dequeueInputElement(): Any = {
    val elem = inputBuffer(nextInputElementCursor)
    inputBuffer(nextInputElementCursor) = null

    batchRemaining -= 1
    if (batchRemaining == 0 && !upstreamCompleted) {
      upstream.requestMore(requestBatchSize)
      batchRemaining = requestBatchSize
    }

    inputBufferElements -= 1
    nextInputElementCursor += 1
    nextInputElementCursor &= IndexMask
    elem
  }

  override def enqueueInputElement(elem: Any): Unit =
    if (isOpen) {
      inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
      inputBufferElements += 1
    }

  override def complete(): Unit = upstreamCompleted = true
  override def cancel(): Unit = {
    if (!upstreamCompleted) upstream.cancel()
    upstreamCompleted = true
  }
  override def isClosed: Boolean = upstreamCompleted

  override def clear(): Unit = {
    Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
    inputBufferElements = 0
  }

  override def inputsDepleted = upstreamCompleted && inputBufferElements == 0
  override def inputsAvailable = inputBufferElements > 0

  override val NeedsInput: TransferState = new TransferState {
    def isReady = inputsAvailable
    def isCompleted = inputsDepleted
  }
  override val NeedsInputOrComplete: TransferState = new TransferState {
    def isReady = inputsAvailable || inputsDepleted
    def isCompleted = false
  }
}
