/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.mutable
import scala.collection.immutable
import scala.collection.immutable.TreeSet
import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.stream.ActorFlowMaterializerSettings
import akka.pattern.pipe
import scala.annotation.tailrec
import akka.actor.Props
import akka.actor.DeadLetterSuppression
import akka.stream.Supervision

/**
 * INTERNAL API
 */
private[akka] object MapAsyncProcessorImpl {

  def props(settings: ActorFlowMaterializerSettings, f: Any ⇒ Future[Any]): Props =
    Props(new MapAsyncProcessorImpl(settings, f))

  object FutureElement {
    implicit val ordering: Ordering[FutureElement] = new Ordering[FutureElement] {
      def compare(a: FutureElement, b: FutureElement): Int = {
        a.seqNo compare b.seqNo
      }
    }
  }

  final case class FutureElement(seqNo: Long, element: Any) extends DeadLetterSuppression
  final case class FutureFailure(cause: Throwable) extends DeadLetterSuppression
  final case class RecoveredError(in: Any, cause: Throwable)
}

/**
 * INTERNAL API
 */
private[akka] class MapAsyncProcessorImpl(_settings: ActorFlowMaterializerSettings, f: Any ⇒ Future[Any])
  extends ActorProcessorImpl(_settings) {
  import MapAsyncProcessorImpl._

  // Execution context for pipeTo and friends
  import context.dispatcher

  val decider = settings.supervisionDecider
  var submittedSeqNo = 0L
  var doneSeqNo = 0L
  def gap: Long = submittedSeqNo - doneSeqNo

  // TODO performance improvement: explore Endre's proposal of using an array based ring buffer addressed by
  //      seqNo & Mask and explicitly storing a Gap object to denote missing pieces instead of the sorted set

  // keep future results arriving too early in a buffer sorted by seqNo
  var orderedBuffer = TreeSet.empty[FutureElement]

  override def activeReceive = futureReceive.orElse[Any, Unit](super.activeReceive)

  def drainBuffer(): List[Any] = {

    // this is mutable for speed
    var n = 0
    var elements = mutable.ListBuffer.empty[Any]
    var failure: Option[Throwable] = None
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

        // Futures are spawned based on downstream demand and therefore we know at this point
        // that the elements can be emitted immediately to downstream
        if (!primaryOutputs.demandAvailable) throw new IllegalStateException

        if (orderedBuffer.isEmpty) {
          emit(element)
        } else {
          emit(element)
          drainBuffer() foreach emit
        }
        pump()
      } else {
        assert(seqNo > doneSeqNo, s"Unexpected sequence number [$seqNo], expected seqNo > $doneSeqNo")
        // out of order, buffer until missing elements arrive
        orderedBuffer += fe
      }

    case FutureFailure(cause) ⇒
      fail(cause)
  }

  def emit(element: Any): Unit = element match {
    case RecoveredError(in, err) ⇒
      if (settings.debugLogging)
        log.debug("Dropped element [{}] due to mapAsync future was completed with exception: {}", in, err.getMessage)
    case elem ⇒
      primaryOutputs.enqueueOutputElement(element)
  }

  override def onError(e: Throwable): Unit = {
    // propagate upstream failure immediately
    fail(e)
  }

  object RunningPhaseCondition extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandCount - gap > 0) ||
      (primaryInputs.inputsDepleted && gap == 0)
    def isCompleted = false
  }

  val running: TransferPhase = TransferPhase(RunningPhaseCondition) { () ⇒
    if (primaryInputs.inputsDepleted) {
      nextPhase(completedPhase)
    } else if (primaryInputs.inputsAvailable && primaryOutputs.demandCount - gap > 0) {
      val elem = primaryInputs.dequeueInputElement()
      try {
        val future = f(elem)
        submittedSeqNo += 1
        val seqNo = submittedSeqNo
        future.map(FutureElement(seqNo, _)).recover {
          case err: Throwable if decider(err) != Supervision.Stop ⇒
            FutureElement(seqNo, RecoveredError(elem, err))
          case err ⇒ FutureFailure(err)
        }.pipeTo(self)
      } catch {
        case NonFatal(err) ⇒
          // f threw, handle failure immediately
          decider(err) match {
            case Supervision.Stop ⇒
              fail(err)
            case Supervision.Resume | Supervision.Restart ⇒
              // submittedSeqNo was not increased, just continue
              if (settings.debugLogging)
                log.debug("Dropped element [{}] due to exception from mapAsync factory function: {}", elem, err.getMessage)
          }

      }
    }
  }

  nextPhase(running)

}
