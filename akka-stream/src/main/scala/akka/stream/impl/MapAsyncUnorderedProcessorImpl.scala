/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.ActorFlowMaterializerSettings
import akka.pattern.pipe
import akka.actor.Props
import akka.actor.DeadLetterSuppression
import akka.stream.Supervision

/**
 * INTERNAL API
 */
private[akka] object MapAsyncUnorderedProcessorImpl {
  def props(settings: ActorFlowMaterializerSettings, f: Any ⇒ Future[Any]): Props =
    Props(new MapAsyncUnorderedProcessorImpl(settings, f))

  final case class FutureElement(element: Any) extends DeadLetterSuppression
  final case class FutureFailure(in: Any, cause: Throwable) extends DeadLetterSuppression
}

/**
 * INTERNAL API
 */
private[akka] class MapAsyncUnorderedProcessorImpl(_settings: ActorFlowMaterializerSettings, f: Any ⇒ Future[Any])
  extends ActorProcessorImpl(_settings) {
  import MapAsyncUnorderedProcessorImpl._

  // Execution context for pipeTo and friends
  import context.dispatcher

  val decider = settings.supervisionDecider
  var inProgressCount = 0

  override def activeReceive = futureReceive.orElse[Any, Unit](super.activeReceive)

  def futureReceive: Receive = {
    case FutureElement(element) ⇒
      // Futures are spawned based on downstream demand and therefore we know at this point
      // that the element can be emitted immediately to downstream
      if (!primaryOutputs.demandAvailable) throw new IllegalStateException

      inProgressCount -= 1
      primaryOutputs.enqueueOutputElement(element)
      pump()

    case FutureFailure(in, err) ⇒
      decider(err) match {
        case Supervision.Stop ⇒
          fail(err)
        case Supervision.Resume | Supervision.Restart ⇒
          inProgressCount -= 1
          if (settings.debugLogging)
            log.debug("Dropped element [{}] due to mapAsyncUnordered future was completed with exception: {}",
              in, err.getMessage)
          pump()
      }
  }

  override def onError(e: Throwable): Unit = {
    // propagate upstream failure immediately
    fail(e)
  }

  object RunningPhaseCondition extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandCount - inProgressCount > 0) ||
      (primaryInputs.inputsDepleted && inProgressCount == 0)
    def isCompleted = primaryOutputs.isClosed
  }

  val running: TransferPhase = TransferPhase(RunningPhaseCondition) { () ⇒
    if (primaryInputs.inputsDepleted) {
      nextPhase(completedPhase)
    } else if (primaryInputs.inputsAvailable && primaryOutputs.demandCount - inProgressCount > 0) {
      val elem = primaryInputs.dequeueInputElement()
      try {
        val future = f(elem)
        inProgressCount += 1
        future.map(FutureElement.apply).recover {
          case err ⇒ FutureFailure(elem, err)
        }.pipeTo(self)
      } catch {
        case NonFatal(err) ⇒
          // f threw, propagate failure immediately
          decider(err) match {
            case Supervision.Stop ⇒
              fail(err)
            case Supervision.Resume | Supervision.Restart ⇒
              // inProgressCount was not increased, just continue
              if (settings.debugLogging)
                log.debug("Dropped element [{}] due to exception from mapAsyncUnordered factory function: {}", elem, err.getMessage)
          }
      }
    }
  }

  nextPhase(running)

}
