/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.{ OverflowStrategy, MaterializerSettings }

class ConflateImpl(_settings: MaterializerSettings, seed: Any ⇒ Any, aggregate: (Any, Any) ⇒ Any) extends ActorProcessorImpl(_settings) {
  var conflated: Any = null

  val waitNextZero: TransferPhase = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    conflated = seed(primaryInputs.dequeueInputElement())
    nextPhase(conflateThenEmit)
  }

  val conflateThenEmit: TransferPhase = TransferPhase(primaryInputs.NeedsInput || primaryOutputs.NeedsDemand) { () ⇒
    if (primaryInputs.inputsAvailable) conflated = aggregate(conflated, primaryInputs.dequeueInputElement())
    if (primaryOutputs.demandAvailable) {
      primaryOutputs.enqueueOutputElement(conflated)
      conflated = null
      nextPhase(waitNextZero)
    }
  }

  nextPhase(waitNextZero)
}

class ExpandImpl(_settings: MaterializerSettings, seed: Any ⇒ Any, extrapolate: Any ⇒ (Any, Any)) extends ActorProcessorImpl(_settings) {
  var extrapolateState: Any = null

  val waitFirst: TransferPhase = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    extrapolateState = seed(primaryInputs.dequeueInputElement())
    nextPhase(emitFirst)
  }

  val emitFirst: TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    emitExtrapolate()
    nextPhase(extrapolateOrReset)
  }

  val extrapolateOrReset: TransferPhase = TransferPhase(primaryInputs.NeedsInputOrComplete || primaryOutputs.NeedsDemand) { () ⇒
    if (primaryInputs.inputsDepleted) nextPhase(completedPhase)
    else if (primaryInputs.inputsAvailable) {
      extrapolateState = seed(primaryInputs.dequeueInputElement())
      nextPhase(emitFirst)
    } else emitExtrapolate()
  }

  def emitExtrapolate(): Unit = {
    val (emit, nextState) = extrapolate(extrapolateState)
    primaryOutputs.enqueueOutputElement(emit)
    extrapolateState = nextState
  }

  nextPhase(waitFirst)
}

class BufferImpl(_settings: MaterializerSettings, size: Int, overflowStrategy: OverflowStrategy) extends ActorProcessorImpl(_settings) {
  import OverflowStrategy._

  val buffer = FixedSizeBuffer(size)

  val dropAction: () ⇒ Unit = overflowStrategy match {
    case DropHead     ⇒ buffer.dropHead
    case DropTail     ⇒ buffer.dropTail
    case DropBuffer   ⇒ buffer.clear
    case Backpressure ⇒ () ⇒ nextPhase(bufferFull)
  }

  val bufferEmpty: TransferPhase = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    buffer.enqueue(primaryInputs.dequeueInputElement())
    nextPhase(bufferNonEmpty)
  }

  val bufferNonEmpty: TransferPhase = TransferPhase(primaryInputs.NeedsInput || primaryOutputs.NeedsDemand) { () ⇒
    if (primaryOutputs.demandAvailable) {
      primaryOutputs.enqueueOutputElement(buffer.dequeue())
      if (buffer.isEmpty) nextPhase(bufferEmpty)
    } else {
      if (buffer.isFull) dropAction()
      else buffer.enqueue(primaryInputs.dequeueInputElement())
    }
  }

  val bufferFull: TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(buffer.dequeue())
    if (buffer.isEmpty) nextPhase(bufferEmpty)
    else nextPhase(bufferNonEmpty)
  }

  nextPhase(bufferEmpty)
}