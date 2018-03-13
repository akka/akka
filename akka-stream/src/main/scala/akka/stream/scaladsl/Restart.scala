/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.pattern.BackoffSupervisor
import akka.stream._
import akka.stream.stage.{ GraphStage, InHandler, OutHandler, TimerGraphStageLogicWithLogging }

import scala.concurrent.duration.FiniteDuration

/**
 * A RestartSource wraps a [[Source]] that gets restarted when it completes or fails.
 *
 * They are useful for graphs that need to run for longer than the [[Source]] can necessarily guarantee it will, for
 * example, for [[Source]] streams that depend on a remote server that may crash or become partitioned. The
 * RestartSource ensures that the graph can continue running while the [[Source]] restarts.
 */
object RestartSource {

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Source]] will never emit a complete or failure, since the completion or failure of the wrapped [[Source]]
   * is always handled by restarting it. The wrapped [[Source]] can however be cancelled by cancelling this [[Source]].
   * When that happens, the wrapped [[Source]], if currently running will be cancelled, and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   */
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(sourceFactory: () ⇒ Source[T, _]): Source[T, NotUsed] = {
    Source.fromGraph(new RestartWithBackoffSource(sourceFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = false, Int.MaxValue))
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Source]] will not emit a complete or failure as long as maxRestarts is not reached, since the completion
   * or failure of the wrapped [[Source]] is handled by restarting it. The wrapped [[Source]] can however be cancelled
   * by cancelling this [[Source]]. When that happens, the wrapped [[Source]], if currently running will be cancelled,
   * and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
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
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   */
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)(sourceFactory: () ⇒ Source[T, _]): Source[T, NotUsed] = {
    Source.fromGraph(new RestartWithBackoffSource(sourceFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = false, maxRestarts))
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails using an exponential backoff.
   *
   * This [[Source]] will never emit a failure, since the failure of the wrapped [[Source]] is always handled by
   * restarting. The wrapped [[Source]] can be cancelled by cancelling this [[Source]].
   * When that happens, the wrapped [[Source]], if currently running will be cancelled, and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   *
   */
  def onFailuresWithBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(sourceFactory: () ⇒ Source[T, _]): Source[T, NotUsed] = {
    Source.fromGraph(new RestartWithBackoffSource(sourceFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = true, Int.MaxValue))
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails using an exponential backoff.
   *
   * This [[Source]] will not emit a complete or failure as long as maxRestarts is not reached, since the completion
   * or failure of the wrapped [[Source]] is handled by restarting it. The wrapped [[Source]] can however be cancelled
   * by cancelling this [[Source]]. When that happens, the wrapped [[Source]], if currently running will be cancelled,
   * and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
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
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   *
   */
  def onFailuresWithBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)(sourceFactory: () ⇒ Source[T, _]): Source[T, NotUsed] = {
    Source.fromGraph(new RestartWithBackoffSource(sourceFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = true, maxRestarts))
  }
}

private final class RestartWithBackoffSource[T](
  sourceFactory:  () ⇒ Source[T, _],
  minBackoff:     FiniteDuration,
  maxBackoff:     FiniteDuration,
  randomFactor:   Double,
  onlyOnFailures: Boolean,
  maxRestarts:    Int) extends GraphStage[SourceShape[T]] { self ⇒

  val out = Outlet[T]("RestartWithBackoffSource.out")

  override def shape = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes) = new RestartWithBackoffLogic(
    "Source", shape, minBackoff, maxBackoff, randomFactor, onlyOnFailures, maxRestarts) {

    override protected def logSource = self.getClass

    override protected def startGraph() = {
      val sinkIn = createSubInlet(out)
      sourceFactory().runWith(sinkIn.sink)(subFusingMaterializer)
      if (isAvailable(out)) {
        sinkIn.pull()
      }
    }

    override protected def backoff() = {
      setHandler(out, new OutHandler {
        override def onPull() = ()
      })
    }

    backoff()
  }
}

/**
 * A RestartSink wraps a [[Sink]] that gets restarted when it completes or fails.
 *
 * They are useful for graphs that need to run for longer than the [[Sink]] can necessarily guarantee it will, for
 * example, for [[Sink]] streams that depend on a remote server that may crash or become partitioned. The
 * RestartSink ensures that the graph can continue running while the [[Sink]] restarts.
 */
object RestartSink {

  /**
   * Wrap the given [[Sink]] with a [[Sink]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Sink]] will never cancel, since cancellation by the wrapped [[Sink]] is always handled by restarting it.
   * The wrapped [[Sink]] can however be completed by feeding a completion or error into this [[Sink]]. When that
   * happens, the [[Sink]], if currently running, will terminate and will not be restarted. This can be triggered
   * simply by the upstream completing, or externally by introducing a [[KillSwitch]] right before this [[Sink]] in the
   * graph.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. When the wrapped [[Sink]] does cancel, this [[Sink]] will backpressure, however any elements already
   * sent may have been lost.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(sinkFactory: () ⇒ Sink[T, _]): Sink[T, NotUsed] = {
    Sink.fromGraph(new RestartWithBackoffSink(sinkFactory, minBackoff, maxBackoff, randomFactor, Int.MaxValue))
  }

  /**
   * Wrap the given [[Sink]] with a [[Sink]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Sink]] will not cancel as long as maxRestarts is not reached, since cancellation by the wrapped [[Sink]]
   * is handled by restarting it. The wrapped [[Sink]] can however be completed by feeding a completion or error into
   * this [[Sink]]. When that happens, the [[Sink]], if currently running, will terminate and will not be restarted.
   * This can be triggered simply by the upstream completing, or externally by introducing a [[KillSwitch]] right
   * before this [[Sink]] in the graph.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. When the wrapped [[Sink]] does cancel, this [[Sink]] will backpressure, however any elements already
   * sent may have been lost.
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
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)(sinkFactory: () ⇒ Sink[T, _]): Sink[T, NotUsed] = {
    Sink.fromGraph(new RestartWithBackoffSink(sinkFactory, minBackoff, maxBackoff, randomFactor, maxRestarts))
  }
}

private final class RestartWithBackoffSink[T](
  sinkFactory:  () ⇒ Sink[T, _],
  minBackoff:   FiniteDuration,
  maxBackoff:   FiniteDuration,
  randomFactor: Double,
  maxRestarts:  Int) extends GraphStage[SinkShape[T]] { self ⇒

  val in = Inlet[T]("RestartWithBackoffSink.in")

  override def shape = SinkShape(in)
  override def createLogic(inheritedAttributes: Attributes) = new RestartWithBackoffLogic(
    "Sink", shape, minBackoff, maxBackoff, randomFactor, onlyOnFailures = false, maxRestarts) {
    override protected def logSource = self.getClass

    override protected def startGraph() = {
      val sourceOut = createSubOutlet(in)
      Source.fromGraph(sourceOut.source).runWith(sinkFactory())(subFusingMaterializer)
    }

    override protected def backoff() = {
      setHandler(in, new InHandler {
        override def onPush() = ()
      })
    }

    backoff()
  }
}

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
  def withBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(flowFactory: () ⇒ Flow[In, Out, _]): Flow[In, Out, NotUsed] = {
    Flow.fromGraph(new RestartWithBackoffFlow(flowFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = false, Int.MaxValue))
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
  def withBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)(flowFactory: () ⇒ Flow[In, Out, _]): Flow[In, Out, NotUsed] = {
    Flow.fromGraph(new RestartWithBackoffFlow(flowFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = false, maxRestarts))
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
  def onFailuresWithBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)(flowFactory: () ⇒ Flow[In, Out, _]): Flow[In, Out, NotUsed] = {
    Flow.fromGraph(new RestartWithBackoffFlow(flowFactory, minBackoff, maxBackoff, randomFactor, onlyOnFailures = true, maxRestarts))
  }

}

private final class RestartWithBackoffFlow[In, Out](
  flowFactory:    () ⇒ Flow[In, Out, _],
  minBackoff:     FiniteDuration,
  maxBackoff:     FiniteDuration,
  randomFactor:   Double,
  onlyOnFailures: Boolean,
  maxRestarts:    Int) extends GraphStage[FlowShape[In, Out]] { self ⇒

  val in = Inlet[In]("RestartWithBackoffFlow.in")
  val out = Outlet[Out]("RestartWithBackoffFlow.out")

  override def shape = FlowShape(in, out)
  override def createLogic(inheritedAttributes: Attributes) = new RestartWithBackoffLogic(
    "Flow", shape, minBackoff, maxBackoff, randomFactor, onlyOnFailures, maxRestarts) {

    var activeOutIn: Option[(SubSourceOutlet[In], SubSinkInlet[Out])] = None

    override protected def logSource = self.getClass

    override protected def startGraph() = {
      val sourceOut = createSubOutlet(in)
      val sinkIn = createSubInlet(out)
      Source.fromGraph(sourceOut.source).via(flowFactory()).runWith(sinkIn.sink)(subFusingMaterializer)
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
        case (sourceOut, sinkIn) ⇒
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
  name:           String,
  shape:          S,
  minBackoff:     FiniteDuration,
  maxBackoff:     FiniteDuration,
  randomFactor:   Double,
  onlyOnFailures: Boolean,
  maxRestarts:    Int) extends TimerGraphStageLogicWithLogging(shape) {
  var restartCount = 0
  var resetDeadline = minBackoff.fromNow
  // This is effectively only used for flows, if either the main inlet or outlet of this stage finishes, then we
  // don't want to restart the sub inlet when it finishes, we just finish normally.
  var finishing = false

  protected def startGraph(): Unit
  protected def backoff(): Unit

  protected final def createSubInlet[T](out: Outlet[T]): SubSinkInlet[T] = {
    val sinkIn = new SubSinkInlet[T](s"RestartWithBackoff$name.subIn")

    sinkIn.setHandler(new InHandler {
      override def onPush() = push(out, sinkIn.grab())
      override def onUpstreamFinish() = {
        if (finishing || maxRestartsReached() || onlyOnFailures) {
          complete(out)
        } else {
          log.debug("Restarting graph due to finished upstream")
          scheduleRestartTimer()
        }
      }
      override def onUpstreamFailure(ex: Throwable) = {
        if (finishing || maxRestartsReached()) {
          fail(out, ex)
        } else {
          log.error(ex, "Restarting graph due to failure")
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

  protected final def createSubOutlet[T](in: Inlet[T]): SubSourceOutlet[T] = {
    val sourceOut = new SubSourceOutlet[T](s"RestartWithBackoff$name.subOut")

    sourceOut.setHandler(new OutHandler {
      override def onPull() = if (isAvailable(in)) {
        sourceOut.push(grab(in))
      } else {
        if (!hasBeenPulled(in)) {
          pull(in)
        }
      }
      override def onDownstreamFinish() = {
        if (finishing || maxRestartsReached() || onlyOnFailures) {
          cancel(in)
        } else {
          log.debug("Graph in finished")
          scheduleRestartTimer()
        }
      }
    })

    setHandler(in, new InHandler {
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

  protected final def maxRestartsReached() = {
    // Check if the last start attempt was more than the minimum backoff
    if (resetDeadline.isOverdue()) {
      log.debug("Last restart attempt was more than {} ago, resetting restart count", minBackoff)
      restartCount = 0
    }
    restartCount == maxRestarts
  }

  // Set a timer to restart after the calculated delay
  protected final def scheduleRestartTimer() = {
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
