/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorProcessorImpl
import akka.stream.impl.Emit
import akka.stream.impl.TransferPhase
import akka.stream.impl.TransferState
import akka.pattern.pipe

/**
 * INTERNAL API
 */
private[akka] object MapAsyncUnorderedProcessorImpl {
  case class FutureElement(element: Any)
  case class FutureFailure(cause: Throwable)
}

/**
 * INTERNAL API
 */
private[akka] class MapAsyncUnorderedProcessorImpl(_settings: MaterializerSettings, f: Any ⇒ Future[Any])
  extends ActorProcessorImpl(_settings) {
  import MapAsyncUnorderedProcessorImpl._

  // Execution context for pipeTo and friends
  import context.dispatcher

  var inProgressCount = 0

  override def activeReceive = futureReceive orElse super.activeReceive

  def futureReceive: Receive = {
    case FutureElement(element) ⇒
      // Futures are spawned based on downstream demand and therefore we know at this point
      // that the element can be emitted immediately to downstream
      if (!primaryOutputs.demandAvailable) throw new IllegalStateException

      inProgressCount -= 1
      primaryOutputs.enqueueOutputElement(element)
      pump()

    case FutureFailure(cause) ⇒
      fail(cause)
  }

  override def onError(e: Throwable): Unit = {
    // propagate upstream error immediately
    fail(e)
  }

  object RunningPhaseCondition extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandCount - inProgressCount > 0) ||
      (primaryInputs.inputsDepleted && inProgressCount == 0)
    def isCompleted = false
  }

  val running: TransferPhase = TransferPhase(RunningPhaseCondition) { () ⇒
    if (primaryInputs.inputsDepleted) {
      nextPhase(completedPhase)
    } else if (primaryInputs.inputsAvailable && primaryOutputs.demandCount - inProgressCount > 0) {
      val elem = primaryInputs.dequeueInputElement()
      inProgressCount += 1
      try {
        f(elem).map(FutureElement.apply).recover {
          case err ⇒ FutureFailure(err)
        }.pipeTo(self)
      } catch {
        case NonFatal(err) ⇒
          // f threw, propagate error immediately
          fail(err)
      }
    }
  }

  nextPhase(running)

}
