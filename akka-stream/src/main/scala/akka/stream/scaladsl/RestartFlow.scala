/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.event.Logging
import akka.pattern.BackoffSupervisor
import akka.stream.Attributes.Attribute
import akka.stream._
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.RestartWithBackoffFlow.Delay
import akka.stream.stage._
import scala.concurrent.duration._

import akka.stream.Attributes.LogLevels

/**
 * A RestartFlow wraps a [[Flow]] that gets restarted when it completes or fails.
 *
 * They are useful for graphs that need to run for longer than the [[Flow]] can necessarily guarantee it will, for
 * example, for [[Flow]] streams that depend on a remote server that may crash or become partitioned. The
 * RestartFlow ensures that the graph can continue running while the [[Flow]] restarts.
 */
object RestartFlow {

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Flow]] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
   * completed. Any termination by the [[Flow]] before that time will be handled by restarting it. Any termination
   * signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's running, and then the [[Flow]]
   * will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  def withBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(
      flowFactory: () => Flow[In, Out, _]): Flow[In, Out, NotUsed] = {
    Flow.fromGraph(
      new RestartWithBackoffFlow(
        flowFactory,
        minBackoff,
        maxBackoff,
        randomFactor,
        onlyOnFailures = false,
        Int.MaxValue))
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Flow]] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
   * completed. Any termination by the [[Flow]] before that time will be handled by restarting it as long as maxRestarts
   * is not reached. Any termination signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's
   * running, and then the [[Flow]] will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  def withBackoff[In, Out](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRestarts: Int)(flowFactory: () => Flow[In, Out, _]): Flow[In, Out, NotUsed] = {
    Flow.fromGraph(
      new RestartWithBackoffFlow(
        flowFactory,
        minBackoff,
        maxBackoff,
        randomFactor,
        onlyOnFailures = false,
        maxRestarts))
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails using an exponential
   * backoff. Notice that this [[Flow]] will not restart on completion of the wrapped flow.
   *
   * This [[Flow]] will not emit any failure
   * The failures by the wrapped [[Flow]] will be handled by
   * restarting the wrapping [[Flow]] as long as maxRestarts is not reached.
   * Any termination signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's
   * running, and then the [[Flow]] will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  def onFailuresWithBackoff[In, Out](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRestarts: Int)(flowFactory: () => Flow[In, Out, _]): Flow[In, Out, NotUsed] = {
    Flow.fromGraph(
      new RestartWithBackoffFlow(flowFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = true, maxRestarts))
  }

}

private final class RestartWithBackoffFlow[In, Out](
    flowFactory: () => Flow[In, Out, _],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    onlyOnFailures: Boolean,
    maxRestarts: Int)
    extends GraphStage[FlowShape[In, Out]] { self =>

  val in = Inlet[In]("RestartWithBackoffFlow.in")
  val out = Outlet[Out]("RestartWithBackoffFlow.out")

  override def shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) =
    new RestartWithBackoffLogic(
      "Flow",
      shape,
      inheritedAttributes,
      minBackoff,
      maxBackoff,
      randomFactor,
      onlyOnFailures,
      maxRestarts) {
      val delay = inheritedAttributes.get[Delay](Delay(50.millis)).duration

      var activeOutIn: Option[(SubSourceOutlet[In], SubSinkInlet[Out])] = None

      override protected def logSource = self.getClass

      override protected def startGraph() = {
        val sourceOut: SubSourceOutlet[In] = createSubOutlet(in)
        val sinkIn: SubSinkInlet[Out] = createSubInlet(out)

        Source
          .fromGraph(sourceOut.source)
          // Temp fix while waiting cause of cancellation. See #23909
          .via(RestartWithBackoffFlow.delayCancellation[In](delay))
          .via(flowFactory())
          .runWith(sinkIn.sink)(subFusingMaterializer)

        if (isAvailable(out)) {
          sinkIn.pull()
        }
        activeOutIn = Some((sourceOut, sinkIn))
      }

      override protected def backoff() = {
        setHandler(in, new InHandler {
          override def onPush() = ()
        })
        setHandler(out, new OutHandler {
          override def onPull() = ()
        })

        // We need to ensure that the other end of the sub flow is also completed, so that we don't
        // receive any callbacks from it.
        activeOutIn.foreach {
          case (sourceOut, sinkIn) =>
            if (!sourceOut.isClosed) {
              sourceOut.complete()
            }
            if (!sinkIn.isClosed) {
              sinkIn.cancel()
            }
            activeOutIn = None
        }
      }

      backoff()
    }
}

/**
 * Shared logic for all restart with backoff logics.
 */
private abstract class RestartWithBackoffLogic[S <: Shape](
    name: String,
    shape: S,
    inheritedAttributes: Attributes,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    onlyOnFailures: Boolean,
    maxRestarts: Int)
    extends TimerGraphStageLogicWithLogging(shape) {
  var restartCount = 0
  var resetDeadline = minBackoff.fromNow

  // This is effectively only used for flows, if either the main inlet or outlet of this stage finishes, then we
  // don't want to restart the sub inlet when it finishes, we just finish normally.
  var finishing = false

  protected def startGraph(): Unit
  protected def backoff(): Unit

  private def loggingEnabled = inheritedAttributes.get[LogLevels] match {
    case Some(levels) => levels.onFailure != LogLevels.Off
    case None         => true
  }

  /**
   * @param out The permanent outlet
   * @return A sub sink inlet that's sink is attached to the wrapped operator
   */
  protected final def createSubInlet[T](out: Outlet[T]): SubSinkInlet[T] = {
    val sinkIn = new SubSinkInlet[T](s"RestartWithBackoff$name.subIn")

    sinkIn.setHandler(new InHandler {
      override def onPush() = push(out, sinkIn.grab())

      override def onUpstreamFinish() = {
        if (finishing || maxRestartsReached() || onlyOnFailures) {
          complete(out)
        } else {
          scheduleRestartTimer()
        }
      }

      /*
       * Upstream in this context is the wrapped stage.
       */
      override def onUpstreamFailure(ex: Throwable) = {
        if (finishing || maxRestartsReached()) {
          fail(out, ex)
        } else {
          if (loggingEnabled)
            log.warning("Restarting graph due to failure. stack_trace: {}", Logging.stackTraceFor(ex))
          scheduleRestartTimer()
        }
      }
    })

    setHandler(out, new OutHandler {
      override def onPull() = sinkIn.pull()
      override def onDownstreamFinish() = {
        finishing = true
        sinkIn.cancel()
      }
    })
    sinkIn
  }

  /**
   * @param in The permanent inlet for this operator
   * @return Temporary SubSourceOutlet for this "restart"
   */
  protected final def createSubOutlet[T](in: Inlet[T]): SubSourceOutlet[T] = {
    val sourceOut = new SubSourceOutlet[T](s"RestartWithBackoff$name.subOut")

    sourceOut.setHandler(new OutHandler {
      override def onPull() =
        if (isAvailable(in)) {
          sourceOut.push(grab(in))
        } else {
          if (!hasBeenPulled(in)) {
            pull(in)
          }
        }

      /*
       * Downstream in this context is the wrapped stage.
       *
       * Can either be a failure or a cancel in the wrapped state.
       * onlyOnFailures is thus racy so a delay to cancellation is added in the case of a flow.
       */
      override def onDownstreamFinish() = {
        if (finishing || maxRestartsReached() || onlyOnFailures) {
          cancel(in)
        } else {
          scheduleRestartTimer()
        }
      }
    })

    setHandler(
      in,
      new InHandler {
        override def onPush() = if (sourceOut.isAvailable) {
          sourceOut.push(grab(in))
        }
        override def onUpstreamFinish() = {
          finishing = true
          sourceOut.complete()
        }
        override def onUpstreamFailure(ex: Throwable) = {
          finishing = true
          sourceOut.fail(ex)
        }
      })

    sourceOut
  }

  protected final def maxRestartsReached(): Boolean = {
    // Check if the last start attempt was more than the minimum backoff
    if (resetDeadline.isOverdue()) {
      log.debug("Last restart attempt was more than {} ago, resetting restart count", minBackoff)
      restartCount = 0
    }
    restartCount == maxRestarts
  }

  // Set a timer to restart after the calculated delay
  protected final def scheduleRestartTimer(): Unit = {
    val restartDelay = BackoffSupervisor.calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
    log.debug("Restarting graph in {}", restartDelay)
    scheduleOnce("RestartTimer", restartDelay)
    restartCount += 1
    // And while we wait, we go into backoff mode
    backoff()
  }

  // Invoked when the backoff timer ticks
  override protected def onTimer(timerKey: Any) = {
    startGraph()
    resetDeadline = minBackoff.fromNow
  }

  // When the stage starts, start the source
  override def preStart() = startGraph()
}

object RestartWithBackoffFlow {

  /**
   * Temporary attribute that can override the time a [[RestartWithBackoffFlow]] waits
   * for a failure before cancelling.
   *
   * See https://github.com/akka/akka/issues/24529
   *
   * Will be removed if/when cancellation can include a cause.
   */
  case class Delay(duration: FiniteDuration) extends Attribute

  /**
   * Returns a flow that is almost identity but delays propagation of cancellation from downstream to upstream.
   *
   * Once the down stream is finish calls to onPush are ignored.
   */
  private def delayCancellation[T](duration: FiniteDuration): Flow[T, T, NotUsed] =
    Flow.fromGraph(new DelayCancellationStage(duration))

  private final class DelayCancellationStage[T](delay: FiniteDuration) extends SimpleLinearGraphStage[T] {

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

        setHandlers(in, out, this)

        def onPush(): Unit = push(out, grab(in))
        def onPull(): Unit = pull(in)

        override def onDownstreamFinish(): Unit = {
          scheduleOnce("CompleteState", delay)
          setHandler(in, new InHandler {
            def onPush(): Unit = {}
          })
        }

        override protected def onTimer(timerKey: Any): Unit = {
          log.debug(s"Stage was canceled after delay of $delay")
          completeStage()
        }
      }
  }
}
