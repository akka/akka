/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.concurrent.duration.FiniteDuration

/**
 * This is a more scalable and general case of [[akka.stream.impl.fusing.GroupedWeightedWithin]]
 * which groups a stream into vectors based on custom weight and time.
 * The problem with that solution is each grouped vector must fit into memory before emitting to the next stage.
 * That won't work for the use case of writing large files.
 * The desirable behavior is to write data as they come as opposed to accumulate everything until the condition is met.
 * In this case, the output channel needs to be closed if there is no data arriving within certain time to avoid connection timeout.
 * This custom flow uses custom aggregator to support such use cases.
 * Upstream inputs are continuously aggregated as they arrive.
 * The aggregator is terminated based on custom condition, interval gap and total duration using timers.
 *
 * @param seed        initiate the aggregated output with first input
 * @param aggregate   sequentially aggregate input into output
 * @param emitReady   decide whether the current aggregated output can be emitted
 * @param maxGap      maximum allowed gap between aggregations, will emit if the interval passed this threshold
 * @param maxDuration optional total duration for the entire aggregator, requiring a separate timer.
 * @param harvest     this is invoked as soon as all conditions are met before emitting to next stage, which could take more time.
 *                    time sensitive operations can be added here, such as closing an output channel
 * @tparam In
 * @tparam Agg
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
  } require(md.gteq(mg), s"maxDuration($md) must not be smaller than maxGap($mg)")

  val in: Inlet[In] = Inlet[In](s"${this.getClass.getName}.in")
  val out: Outlet[Out] = Outlet[Out](s"${this.getClass.getName}.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  val maxDurationTimer = "maxDurationTimer"
  val maxGapTimer = "maxGapTimer"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      override protected def onTimer(timerKey: Any): Unit = state.harvestOnTimer()

      override def onPush(): Unit = {
        state.aggregate()
        if (isAvailable(out)) pull(in) // pull only if there is no backpressure from outlet
      }

      override def onUpstreamFinish(): Unit = {
        state.harvestOnFlush()
        completeStage()
      }

      override def onPull(): Unit = {
        state.aggregate()
        if (!hasBeenPulled(in)) pull(in) // always pass through the pull to upstream
      }

      setHandlers(in, out, this)

      // mutable state to keep track of the aggregator status to coordinate the flow
      // input/output handler callbacks are guaranteed to execute without concurrency
      // https://doc.akka.io/docs/akka/current/stream/stream-customize.html#thread-safety-of-custom-operators
      private val state = new State()

      class State {

        private var aggregator: Option[AggregatorState] = None

        def harvestOnTimer(): Unit = harvestWithMode(mode = HarvestMode.OnTimer)

        def harvestOnFlush(): Unit = harvestWithMode(mode = HarvestMode.Flush)

        private def harvestWithMode(mode: HarvestMode.Value): Unit = {
          aggregator foreach {
            case agg =>
              if (mode == HarvestMode.Flush || emitReady(agg.aggregator) || {
                mode == HarvestMode.OnTimer && {
                  val currentTime = System.currentTimeMillis()
                  // check gap only on timer
                  maxGap.exists(mg => currentTime - agg.lastAggregateTimeMs >= mg.toMillis) ||
                    maxDuration.exists(md => currentTime - agg.startTime >= md.toMillis)
                }
              }) {
                aggregator = None
                emit(out, FoldWithin.this.harvest(agg.aggregator)) // if out port not available, it'll follow up as scheduled emit
              } else {
                // schedule the next gap timer if the aggregator is not harvested and emitted
                maxGap.foreach(mg => scheduleOnce(maxGapTimer, mg))
              }
          }
        }

        def aggregate(): Unit = if (isAvailable(in)) {
          val input = grab(in)
          aggregator match {
            case Some(agg) => aggregator = Some(agg.aggregate(input))
            case None => aggregator = Some(new AggregatorState(input))
              // schedule a timer for max duration for new aggregator
              maxDuration.foreach(md => scheduleOnce(maxDurationTimer, md))
          }
          harvestWithMode(HarvestMode.AfterAggregation)
        }
      }

      object HarvestMode extends Enumeration {
        val AfterAggregation, OnTimer, Flush = Value
      }

      class AggregatorState(input: In) {

        val startTime = System.currentTimeMillis()

        var lastAggregateTimeMs: Long = startTime

        var aggregator: Agg = seed(input)

        def aggregate(input: In): AggregatorState = {
          aggregator = FoldWithin.this.aggregate(aggregator, input)
          lastAggregateTimeMs = System.currentTimeMillis()
          this
        }

      }

    }

}