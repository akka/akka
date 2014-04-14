/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.spi.{ Subscriber, Subscription }
import java.util.Arrays
import scala.util.control.NonFatal
import akka.actor.ActorRefFactory

/**
 * INTERNAL API
 */
private[akka] trait Inputs {
  def NeedsInput: TransferState
  def NeedsInputOrComplete: TransferState

  def enqueueInputElement(elem: Any): Unit
  def dequeueInputElement(): Any

  def cancel(): Unit
  def complete(): Unit
  def isClosed: Boolean
  def isOpen: Boolean = !isClosed

  def prefetch(): Unit

  def inputsDepleted: Boolean
  def inputsAvailable: Boolean
}

/**
 * INTERNAL API
 */
private[akka] trait DefaultInputTransferStates extends Inputs {
  override val NeedsInput: TransferState = new TransferState {
    def isReady = inputsAvailable
    def isCompleted = inputsDepleted
  }
  override val NeedsInputOrComplete: TransferState = new TransferState {
    def isReady = inputsAvailable || inputsDepleted
    def isCompleted = false
  }
}

/**
 * INTERNAL API
 */
private[akka] trait Outputs {
  def NeedsDemand: TransferState
  def NeedsDemandOrCancel: TransferState

  def demandAvailable: Boolean
  def enqueueOutputElement(elem: Any): Unit

  def complete(): Unit
  def cancel(e: Throwable): Unit
  def isClosed: Boolean
  def isOpen: Boolean = !isClosed
}

/**
 * INTERNAL API
 */
private[akka] trait DefaultOutputTransferStates extends Outputs {
  override val NeedsDemand: TransferState = new TransferState {
    def isReady = demandAvailable
    def isCompleted = isClosed
  }
  override def NeedsDemandOrCancel: TransferState = new TransferState {
    def isReady = demandAvailable || isClosed
    def isCompleted = false
  }
}

// States of the operation that is executed by this processor
/**
 * INTERNAL API
 */
private[akka] trait TransferState {
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

/**
 * INTERNAL API
 */
private[akka] object Completed extends TransferState {
  def isReady = false
  def isCompleted = true
}

/**
 * INTERNAL API
 */
private[akka] object NotInitialized extends TransferState {
  def isReady = false
  def isCompleted = false
}

/**
 * INTERNAL API
 */
private[akka] object EmptyInputs extends Inputs {
  override def inputsAvailable: Boolean = false
  override def inputsDepleted: Boolean = true
  override def isClosed: Boolean = true

  override def complete(): Unit = ()
  override def cancel(): Unit = ()
  override def prefetch(): Unit = ()

  override def dequeueInputElement(): Any = throw new UnsupportedOperationException("Cannot dequeue from EmptyInputs")
  override def enqueueInputElement(elem: Any): Unit = throw new UnsupportedOperationException("Cannot enqueue to EmptyInputs")

  override val NeedsInputOrComplete: TransferState = new TransferState {
    override def isReady: Boolean = true
    override def isCompleted: Boolean = false
  }
  override val NeedsInput: TransferState = Completed
}

/**
 * INTERNAL API
 */
private[akka] trait Pump {
  protected def pumpContext: ActorRefFactory
  private var transferState: TransferState = NotInitialized
  def setTransferState(t: TransferState): Unit = transferState = t

  def isPumpFinished: Boolean = transferState.isCompleted

  // Exchange input buffer elements and output buffer "requests" until one of them becomes empty.
  // Generate upstream requestMore for every Nth consumed input element
  final def pump(): Unit = {
    try while (transferState.isExecutable) {
      transferState = ActorBasedFlowMaterializer.withCtx(pumpContext)(transfer())
    } catch { case NonFatal(e) ⇒ pumpFailed(e) }

    if (isPumpFinished) pumpFinished()
  }

  protected def pumpFailed(e: Throwable): Unit
  protected def pumpFinished(): Unit

  // Needs to be implemented by Processor implementations. Transfers elements from the input buffer to the output
  // buffer.
  protected def transfer(): TransferState
}

/**
 * INTERNAL API
 */
private[akka] class BatchingInputBuffer(val upstream: Subscription, val size: Int) extends DefaultInputTransferStates {
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
      if (inputBufferElements == size) throw new IllegalStateException("Input buffer overrun")
      inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
      inputBufferElements += 1
    }

  override def complete(): Unit = upstreamCompleted = true
  override def cancel(): Unit = {
    if (!upstreamCompleted) {
      upstreamCompleted = true
      upstream.cancel()
      clear()
    }
  }
  override def isClosed: Boolean = upstreamCompleted

  private def clear(): Unit = {
    Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
    inputBufferElements = 0
  }

  override def inputsDepleted = upstreamCompleted && inputBufferElements == 0
  override def inputsAvailable = inputBufferElements > 0

}

/**
 * INTERNAL API
 */
private[akka] abstract class FanoutOutputs(val maxBufferSize: Int, val initialBufferSize: Int) extends DefaultOutputTransferStates with SubscriberManagement[Any] {
  private var downstreamBufferSpace = 0
  private var downstreamCompleted = false
  def demandAvailable = downstreamBufferSpace > 0

  def enqueueOutputDemand(demand: Int): Unit = downstreamBufferSpace += demand
  def enqueueOutputElement(elem: Any): Unit = {
    downstreamBufferSpace -= 1
    pushToDownstream(elem)
  }

  def complete(): Unit =
    if (!downstreamCompleted) {
      downstreamCompleted = true
      completeDownstream()
    }

  def cancel(e: Throwable): Unit =
    if (!downstreamCompleted) {
      downstreamCompleted = true
      abortDownstream(e)
    }

  def isClosed: Boolean = downstreamCompleted

  def handleRequest(subscription: S, elements: Int): Unit = super.moreRequested(subscription, elements)
  def addSubscriber(subscriber: Subscriber[Any]): Unit = super.registerSubscriber(subscriber)
  def removeSubscription(subscription: S): Unit = super.unregisterSubscription(subscription)

  def afterShutdown(completed: Boolean): Unit

  override protected def requestFromUpstream(elements: Int): Unit = enqueueOutputDemand(elements)
  override protected def shutdown(completed: Boolean): Unit = afterShutdown(completed)
  override protected def cancelUpstream(): Unit = {
    downstreamCompleted = true
  }
}
