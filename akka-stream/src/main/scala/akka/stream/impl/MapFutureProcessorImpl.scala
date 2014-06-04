/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.mutable
import scala.collection.immutable
import scala.collection.immutable.TreeSet
import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.stream.MaterializerSettings
import akka.pattern.pipe
import scala.annotation.tailrec

/**
 * INTERNAL API
 */
private[akka] object MapFutureProcessorImpl {

  object FutureElement {
    implicit val ordering: Ordering[FutureElement] = new Ordering[FutureElement] {
      def compare(a: FutureElement, b: FutureElement): Int = {
        a.seqNo compare b.seqNo
      }
    }
  }

  case class FutureElement(seqNo: Long, element: Any)
  case class FutureFailure(cause: Throwable)
}

/**
 * INTERNAL API
 */
private[akka] class MapFutureProcessorImpl(_settings: MaterializerSettings, f: Any ⇒ Future[Any]) extends ActorProcessorImpl(_settings) {
  import MapFutureProcessorImpl._

  // Execution context for pipeTo and friends
  import context.dispatcher

  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]

  var submittedSeqNo = 0L
  var doneSeqNo = 0L
  def gap: Long = submittedSeqNo - doneSeqNo

  // TODO performance improvement: explore Endre's proposal of using an array based ring buffer addressed by 
  //      seqNo & Mask and explicitly storing a Gap object to denote missing pieces instead of the sorted set

  // keep future results arriving too early in a buffer sorted by seqNo
  var orderedBuffer = TreeSet.empty[FutureElement]

  override def receive = futureReceive orElse super.receive

  def drainBuffer(): List[Any] = {

    // this is mutable for speed
    var n = 0
    var elements = mutable.ListBuffer.empty[Any]
    var error: Option[Throwable] = None
    val iter = orderedBuffer.iterator
    @tailrec def split(): Unit =
      if (iter.hasNext) {
        val next = iter.next()
        val inOrder = next.seqNo == (doneSeqNo + 1)
        // stop at first missing seqNo 
        if (inOrder) {
          n += 1
          doneSeqNo = next.seqNo
          elements += next.element
          split()
        }
      }

    split()
    orderedBuffer = orderedBuffer.drop(n)
    elements.toList
  }

  def futureReceive: Receive = {
    case fe @ FutureElement(seqNo, element) ⇒
      if (seqNo == (doneSeqNo + 1)) {
        // successful element for the next sequence number
        // emit that element and all elements from the buffer that are in order
        // until next missing sequence number
        doneSeqNo = seqNo
        if (orderedBuffer.isEmpty) {
          emits = List(element)
        } else {
          val fromBuffer = drainBuffer()
          emits = element :: fromBuffer
        }
        emitAndThen(running)
        pump()
      } else {
        assert(seqNo > doneSeqNo, s"Unexpected sequence number [$seqNo], expected seqNo > $doneSeqNo")
        // out of order, buffer until missing elements arrive
        orderedBuffer += fe
      }

    case FutureFailure(cause) ⇒
      fail(cause)
  }

  override def onError(e: Throwable): Unit = {
    // propagate upstream error immediately
    fail(e)
  }

  object RunningPhaseCondition extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandCount - gap > 0) ||
      (primaryInputs.inputsDepleted && gap == 0)
    def isCompleted = false
  }

  val running: TransferPhase = TransferPhase(RunningPhaseCondition) { () ⇒
    if (primaryInputs.inputsDepleted) {
      emitAndThen(completedPhase)
    } else if (primaryInputs.inputsAvailable && primaryOutputs.demandCount - gap > 0) {
      val elem = primaryInputs.dequeueInputElement()
      submittedSeqNo += 1
      val seqNo = submittedSeqNo
      try {
        f(elem).map(FutureElement(seqNo, _)).recover {
          case err ⇒ FutureFailure(err)
        }.pipeTo(self)
      } catch {
        case NonFatal(err) ⇒
          // f threw, propagate error immediately
          fail(err)
      }
      emitAndThen(running)
    }
  }

  // Save previous phase we should return to in a var to avoid allocation
  var phaseAfterFlush: TransferPhase = _

  // Enters flushing phase if there are emits pending
  def emitAndThen(andThen: TransferPhase): Unit =
    if (emits.nonEmpty) {
      phaseAfterFlush = andThen
      nextPhase(emitting)
    } else nextPhase(andThen)

  // Emits all pending elements, then returns to savedPhase
  val emitting = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(emits.head)
    emits = emits.tail
    if (emits.isEmpty) nextPhase(phaseAfterFlush)
  }

  nextPhase(running)

}
