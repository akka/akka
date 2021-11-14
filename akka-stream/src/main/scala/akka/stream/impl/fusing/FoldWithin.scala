/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

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
 * The aggregator/grouping can be terminated and emitted based on custom coditions
 *
 * @param seed        initiate the aggregated output with first input
 * @param aggregate   sequentially aggregate input
 * @param emitOnAgg   decide whether the current aggregator can be emitted, invoked after each aggregate
 * @param emitOnTimer decide whether the current aggregator can be emitted, invoked after each timer event
 * @param harvest     this is invoked as soon as all conditions are met before emitting to next stage
 *                    there can be extra time between harvest and next stage receiving the emitted output
 *                    time sensitive operations can be added here, such as closing an output channel
 */
class FoldWith[In, Agg, Out](
    seed: In => Agg,
    aggregate: (Agg, In) => Agg,
    emitOnAgg: Agg => Boolean,
    harvest: Agg => Out,
    emitOnTimer: Option[(Agg => Boolean, FiniteDuration)] = None,
    var maxBufferSize: Int = 1)
    extends GraphStage[FlowShape[In, Out]] {

  require(maxBufferSize >= 1, s"maxBufferSize=$maxBufferSize, must be positive")

  emitOnTimer.foreach {
    case (_, interval) => require(interval.gteq(1.milli), s"timer(${interval.toCoarsest}) must not be smaller than 1ms")
  }

  val in: Inlet[In] = Inlet[In](s"${this.getClass.getName}.in")
  val out: Outlet[Out] = Outlet[Out](s"${this.getClass.getName}.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private[this] var aggregator: Agg = null.asInstanceOf[Agg]

      private val buffer = mutable.Queue[Out]()

      private def bufferFull: Boolean = buffer.size >= maxBufferSize

      override def preStart(): Unit = {
        pull(in)
        emitOnTimer.foreach {
          case (_, interval) => scheduleWithFixedDelay("FoldWithinIntervalTimer", interval, interval)
        }
      }

      override protected def onTimer(timerKey: Any): Unit = {
        emitOnTimer.foreach {
          case (isReadyOnTimer, _) => if (aggregator != null && isReadyOnTimer(aggregator)) {
            println(s"onTimer before harvest $state")
            harvestAndEnqueue()
            println(s"onTimer after harvest $state")
            if (isAvailable(out)) push(out, buffer.dequeue())
            println(s"onTimer final $state")
          }
        }
      }

      private def state = s"buffer=$buffer, agg=$aggregator, isAvailable(out)=${isAvailable(out)}"

      // at onPush, isAvailable(in)=true hasBeenPulled(in)=false, isAvailable(out) could be true or false
      override def onPush(): Unit = {
        val input = grab(in)
        println(s"onPush($input) before $state")
        aggregator = if (aggregator == null) seed(input) else aggregate(aggregator, input)
        if (emitOnAgg(aggregator)) harvestAndEnqueue()
        if (isAvailable(out) && buffer.nonEmpty) push(out, buffer.dequeue())
        if (!bufferFull) pull(in)
        println(s"onPush($input) after $state")
      }

      override def onUpstreamFinish(): Unit = {
        println(s"onComplete $state")
        if (buffer.nonEmpty) emitMultiple(out, buffer.iterator)
        if (aggregator != null) emit(out, harvest(aggregator))
        completeStage()
      }

      // at onPull, isAvailable(out) is always true indicating downstream is waiting
      // isAvailable(in) and hasBeenPulled(in) can be (true, false) (false, true) or (false, false)
      override def onPull(): Unit = {
        println(s"onPull before $state")
        if (buffer.nonEmpty) push(out, buffer.dequeue())
        if (!bufferFull && !hasBeenPulled(in)) pull(in)
        println(s"onPull after $state")
      }

      setHandlers(in, out, this)

      private def harvestAndEnqueue(): Unit = {
        buffer.enqueue(harvest(aggregator))
        aggregator = null.asInstanceOf[Agg]
      }

    }

}

/**
 * This is a convenient wrapper of [[FoldWith]] to handle timing constraints
 * @param seed        initiate the aggregated output with first input
 * @param aggregate   sequentially aggregate input
 * @param emitOnAgg   decide whether the current aggregator can be emitted, invoked after each aggregate
 * @param harvest     this is invoked as soon as all conditions are met before emitting to next stage
 *                    there can be extra time between harvest and next stage receiving the emitted output
 *                    time sensitive operations can be added here, such as closing an output channel
 * @param maxGap      the gap allowed between consecutive aggregate operations
 * @param maxDuration the duration of the sequence of aggregate operations from initial seed until emit is triggered
 * @param interval    interval of the timer to check the maxGap and maxDuration condition
 * @param getSystemTimeMs source of the system time, in case of testing simulated time can be used
 */
class FoldWithin[In, Agg, Out](
    seed: In => Agg,
    aggregate: (Agg, In) => Agg,
    emitOnAgg: Agg => Boolean,
    harvest: Agg => Out,
    maxGap: Option[FiniteDuration] = None,
    maxDuration: Option[FiniteDuration] = None,
    interval: FiniteDuration = 1.milli,
    getSystemTimeMs: => Long = System.currentTimeMillis())
    extends FoldWith[In, ValueTimeWrapper[Agg], Out](
      seed = in => new ValueTimeWrapper(firstTime = getSystemTimeMs, value = seed(in)),
      aggregate = (agg, in) => {
        // user provided aggregate lambda needs to avoid allocation for better performance
        agg.value = aggregate(agg.value, in)
        agg.lastTime = getSystemTimeMs
        // avoid allocation on each aggregate
        agg
      },
      emitOnAgg = agg => emitOnAgg(agg.value),
      harvest = agg => harvest(agg.value),
      emitOnTimer = Some((agg => {
        val currentTime = getSystemTimeMs
        maxDuration.exists(md => currentTime - agg.firstTime >= md.toMillis) ||
        maxGap.exists(mg => currentTime - agg.lastTime >= mg.toMillis)
      }, interval))
    ) {
  require(maxDuration.nonEmpty || maxGap.nonEmpty, "requires timing condition otherwise should use FoldWith")
}

// mutable class to avoid allocating new objects on each aggregate
class ValueTimeWrapper[T](val firstTime: Long, var value: T) {
  var lastTime: Long = firstTime

  override def toString: String = s"($value,first=$firstTime,last$lastTime)"
}
