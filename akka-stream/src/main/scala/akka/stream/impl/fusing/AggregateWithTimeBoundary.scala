/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * This is a more scalable and general case of [[GroupedWeightedWithin]]
 * which groups a stream into vectors based on custom weight and time.
 * The problem with that solution is each grouped vector must fit into memory before emitting to the next stage.
 * That won't work for the use case of writing large files.
 * The desirable behavior is to write data as they come as opposed to accumulate everything until the group slicing condition is met.
 * In this case, the output channel needs to be closed if there is no data arriving within certain time to avoid connection timeout.
 * This custom flow uses custom aggregator to support such use cases.
 * Upstream inputs are continuously aggregated as they arrive.
 * The currently aggregated elements can be terminated and emitted based on custom conditions
 *
 * @param allocate    allocate the initial data structure for aggregated elements
 * @param aggregate   update the aggregated elements, return true if ready to emit after update
 * @param emitOnTimer decide whether the currently aggregated elements can be emitted on each timer event
 * @param harvest     this is invoked before emit within the current stage/operator
 * @param bufferSize  internal buffer to decouple upstream and downstream
 */
class AggregateWithBoundary[T, Agg, Emit](
    allocate: => Agg,
    aggregate: (Agg, T) => Boolean,
    harvest: Agg => Emit,
    emitOnTimer: Option[(Agg => Boolean, FiniteDuration)],
    bufferSize: Int)
    extends GraphStage[FlowShape[T, Emit]] {

  require(bufferSize >= 1, s"maxBufferSize=$bufferSize, must be positive")

  emitOnTimer.foreach {
    case (_, interval) => require(interval.gteq(1.milli), s"timer(${interval.toCoarsest}) must not be smaller than 1ms")
  }

  val in: Inlet[T] = Inlet[T](s"${this.getClass.getName}.in")
  val out: Outlet[Emit] = Outlet[Emit](s"${this.getClass.getName}.out")
  override val shape: FlowShape[T, Emit] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private[this] var aggregated: Agg = null.asInstanceOf[Agg]

      private val buffer = mutable.Queue[Emit]()

      private def bufferFull: Boolean = buffer.size >= bufferSize

      override def preStart(): Unit = {
        pull(in)
        emitOnTimer.foreach {
          case (_, interval) => scheduleWithFixedDelay(s"${this.getClass.getSimpleName}Timer", interval, interval)
        }
      }

      override protected def onTimer(timerKey: Any): Unit = {
        emitOnTimer.foreach {
          case (isReadyOnTimer, _) => if (aggregated != null && isReadyOnTimer(aggregated)) harvestAndEmitOrEnqueue()
        }
      }

      // at onPush, isAvailable(in)=true hasBeenPulled(in)=false, isAvailable(out) could be true or false
      override def onPush(): Unit = {
        val input = grab(in)
        if (aggregated == null) aggregated = allocate
        if (aggregate(aggregated, input)) harvestAndEmitOrEnqueue()
        if (!bufferFull) pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (buffer.nonEmpty) emitMultiple(out, buffer.iterator)
        if (aggregated != null) emit(out, harvest(aggregated))
        completeStage()
      }

      // at onPull, isAvailable(out) is always true indicating downstream is waiting
      // isAvailable(in) and hasBeenPulled(in) can be (true, false) (false, true) or (false, false)
      override def onPull(): Unit = {
        if (buffer.nonEmpty) push(out, buffer.dequeue())
        if (!bufferFull && !hasBeenPulled(in)) pull(in)
      }

      setHandlers(in, out, this)

      private def harvestAndEmitOrEnqueue(): Unit = {
        val output = harvest(aggregated)
        if (isAvailable(out)) push(out, output) else buffer.enqueue(output)
        aggregated = null.asInstanceOf[Agg]
      }

    }

}

/**
 * This is a convenient wrapper of [[AggregateWithBoundary]] to handle 2 kinds of time constraints
 *
 * @param maxGap        the gap allowed between consecutive aggregate operations
 * @param maxDuration   the duration of the sequence of aggregate operations from initial seed until emit is triggered
 * @param interval      interval of the timer to check the maxGap and maxDuration condition
 * @param currentTimeMs source of the system time, in case of testing simulated time can be used
 */
class AggregateWithTimeBoundary[T, Agg, Emit](
    allocate: => Agg,
    aggregate: (Agg, T) => Boolean,
    harvest: Agg => Emit,
    maxGap: Option[FiniteDuration],
    maxDuration: Option[FiniteDuration],
    interval: FiniteDuration,
    currentTimeMs: => Long,
    bufferSize: Int)
    extends AggregateWithBoundary[T, ValueTimeWrapper[Agg], Emit](
      allocate = new ValueTimeWrapper(value = allocate),
      aggregate = (agg, in) => {
        agg.updateTime(currentTimeMs)
        // user provided aggregate lambda needs to avoid allocation for better performance
        aggregate(agg.value, in)
      },
      harvest = agg => harvest(agg.value),
      emitOnTimer = Some((agg => {
        val currentTime = currentTimeMs
        maxDuration.exists(md => currentTime - agg.firstTime >= md.toMillis) ||
        maxGap.exists(mg => currentTime - agg.lastTime >= mg.toMillis)
      }, interval)),
      bufferSize = bufferSize) {
  require(
    maxDuration.nonEmpty || maxGap.nonEmpty,
    s"requires timing condition otherwise should use ${classOf[AggregateWithBoundary[T, Agg, Emit]]}")
}

// mutable class to avoid allocating new objects on each update
class ValueTimeWrapper[T](var value: T) {
  var firstTime: Long = -1
  var lastTime: Long = -1
  def updateTime(time: Long): Unit = {
    if (firstTime == -1) firstTime = time
    lastTime = time
  }
}
