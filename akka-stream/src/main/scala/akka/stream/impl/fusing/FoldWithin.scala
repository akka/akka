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
  } require(md.gteq(mg), s"maxDuration(${md.toCoarsest}) must not be smaller than maxGap(${mg.toCoarsest})")

  val in: Inlet[In] = Inlet[In](s"${this.getClass.getName}.in")
  val out: Outlet[Out] = Outlet[Out](s"${this.getClass.getName}.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      // mutable state to keep track of the aggregator status to coordinate the flow
      // input/output handler callbacks are guaranteed to execute without concurrency
      // https://doc.akka.io/docs/akka/current/stream/stream-customize.html#thread-safety-of-custom-operators
      private var aggregator: OptionVal[AggregatorState] = OptionVal.None

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
        aggregator.toOption.foreach(
          agg => emit(out, FoldWithin.this.harvest(agg.aggregator)) // if out port not available, it'll follow up as scheduled emit
        )
        aggregator = OptionVal.None
      }

      private def readyToEmitByTimeout: Boolean = {
        aggregator.toOption.exists { agg =>
          val currentTime = System.nanoTime()
          // check gap only on timer
          maxGap.exists(mg => currentTime - agg.lastAggregateTime >= mg.toNanos) ||
            maxDuration.exists(md => currentTime - agg.startTime >= md.toNanos)
        }
      }

      private def aggregateAndEmitIfReady(): Unit = if (isAvailable(in)) {
        val input = grab(in)
        aggregator.toOption match {
          case Some(agg) => agg.aggregate(input)
          case None => aggregator = OptionVal.Some(new AggregatorState(input))
        }

        aggregator.toOption.foreach {
          agg => if (emitReady(agg.aggregator)) harvestAndEmit()
        }
      }

      class AggregatorState(input: In) {

        val startTime = System.nanoTime()

        var lastAggregateTime: Long = startTime

        var aggregator: Agg = seed(input)

        def aggregate(input: In): AggregatorState = {
          aggregator = FoldWithin.this.aggregate(aggregator, input)
          lastAggregateTime = System.nanoTime()
          this
        }

      }

    }

}