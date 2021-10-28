/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.OptionVal

import scala.concurrent.duration._

/**
 * This is a more scalable and general case of [[akka.stream.impl.fusing.GroupedWeightedWithin]]
 * which groups a stream into vectors based on custom weight and time.
 * The problem with that solution is each grouped vector must fit into memory before emitting to the next stage.
 * That won't work for the use case of writing large files.
 * The desirable behavior is to write data as they come as opposed to accumulate everything until the group slicing condition is met.
 * In this case, the output channel needs to be closed if there is no data arriving within certain time to avoid connection timeout.
 * This custom flow uses custom aggregator to support such use cases.
 * Upstream inputs are continuously aggregated as they arrive.
 * The aggregator/grouping can be terminated and emitted in 3 ways
 * 1. custom emit condition
 * 2. interval gap and total duration using timers
 * 3. flush onUpstreamFinish
 *
 * @param seed        initiate the aggregated output with first input
 * @param aggregate   sequentially aggregate input
 * @param emitReady   decide whether the current aggregator can be emitted
 * @param maxGap      maximum allowed gap between aggregations, will emit if the interval passed this threshold
 * @param maxDuration total duration for the entire aggregator
 * @param harvest     this is invoked as soon as all conditions are met before emitting to next stage
 *                    there can be extra time between harvest and next stage receiving the emitted output
 *                    time sensitive operations can be added here, such as closing an output channel
 */
class FoldWithin[In, Agg, Out](
                                val seed: In => Agg,
                                val aggregate: (Agg, In) => Agg,
                                val emitReady: Agg => Boolean,
                                val harvest: Agg => Out,
                                val maxGap: Option[FiniteDuration] = None,
                                val maxDuration: Option[FiniteDuration] = None
                              )
  extends GraphStage[FlowShape[In, Out]] {

  // maxDuration must not be smaller than the maxGap, otherwise maxGap is meaningless
  for {
    md <- maxDuration
    mg <- maxGap
  } require(md.gteq(mg), s"maxDuration(${md.toCoarsest}) must not be smaller than maxGap(${mg.toCoarsest})")

  val in: Inlet[In] = Inlet[In](s"${this.getClass.getName}.in")
  val out: Outlet[Out] = Outlet[Out](s"${this.getClass.getName}.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      // mutable state to keep track of the aggregator status to coordinate the flow
      // input/output handler callbacks are guaranteed to execute without concurrency
      // https://doc.akka.io/docs/akka/current/stream/stream-customize.html#thread-safety-of-custom-operators
      private[this] var aggregator: OptionVal[AggregatorState] = OptionVal.None

      override def preStart(): Unit = {
        // although we do not need timer events when the group is empty
        // it's more efficient to set up a upfront timer with fixed interval
        // as opposed to time every single event
        // always pick the smaller of the two intervals and divide by 2
        Option(maxGap.getOrElse(maxDuration.getOrElse(null))).map(_ / 2).map {
          delay => scheduleWithFixedDelay("FoldWithinIntervalTimer", delay, delay)
        }
      }

      override protected def onTimer(timerKey: Any): Unit = if (readyToEmitByTimeout) harvestAndEmit()

      override def onPush(): Unit = {
        aggregateAndEmitIfReady()
        if (isAvailable(out)) pull(in) // pull only if there is no backpressure from outlet
      }

      override def onUpstreamFinish(): Unit = {
        harvestAndEmit()
        completeStage()
      }

      override def onPull(): Unit = {
        aggregateAndEmitIfReady()
        if (!hasBeenPulled(in)) pull(in) // always pass through the pull to upstream
      }

      setHandlers(in, out, this)

      private def harvestAndEmit(): Unit = {
        aggregator match {
          case OptionVal.Some(agg) =>
            emit(out, FoldWithin.this.harvest(agg.agg))
            aggregator = OptionVal.None
          case _ =>
        }

      }

      private def readyToEmitByTimeout: Boolean = {
        aggregator match {
          case OptionVal.Some(agg) =>
            val currentTime = System.nanoTime()
            maxGap.exists(mg => currentTime - agg.lastAggregateTime >= mg.toNanos) ||
              maxDuration.exists(md => currentTime - agg.startTime >= md.toNanos)
          case _ => false
        }
      }

      private def aggregateAndEmitIfReady(): Unit = if (isAvailable(in)) {
        val input = grab(in)
        aggregator match {
          case OptionVal.Some(agg) => agg.aggregate(input)
          case _ => aggregator = OptionVal.Some(new AggregatorState(input))
        }
        // aggregator must not be None at this point
        if (emitReady(aggregator.get.agg)) harvestAndEmit()
      }

      private[this] class AggregatorState(input: In) {

        val startTime = System.nanoTime()

        var lastAggregateTime: Long = startTime

        private[this] var aggregator: Agg = seed(input)
        def agg: Agg = aggregator

        def aggregate(input: In): Unit = {
          aggregator = FoldWithin.this.aggregate(aggregator, input)
          lastAggregateTime = System.nanoTime()
        }

      }

    }

}