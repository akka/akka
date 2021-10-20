/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

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
 * @param seed initiate the aggregated output with first input
 * @param aggregate sequentially aggregate input into output
 * @param emitReady decide whether the current aggregated output can be emitted
 * @param maxGap maximum allowed gap between aggregations, will emit if the interval passed this threshold
 * @param maxDuration optional total duration for the entire aggregator, requiring a separate timer.
 * @param harvest  this is invoked as soon as all conditions are met before emitting to next stage, which could take more time.
 *                 time sensitive operations can be added here, such as closing an output channel
 * @tparam In
 * @tparam Out
 */
class Aggregator[In, Out](
    val seed: In => Out,
    val aggregate: (Out, In) => Out,
    val emitReady: Out => Boolean,
    val maxGap: Option[FiniteDuration] = None,
    val maxDuration: Option[FiniteDuration] = None,
    val harvest: Option[Out => Out] = None)
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
    new TimerGraphStageLogic(shape) {

      override def preStart() = pull(in)

      // there are two timers one for gap one for total duration
      // this can potentially harvest the current aggregator and start the loop from emit
      override protected def onTimer(timerKey: Any): Unit =
        if (state.harvestAttempt(mode = HarvestMode.OnTimer)) emitAndLoop()

      setHandler(in, new InHandler {

        // this callback is triggered after upstream push with new data
        // so the loop start from aggregate
        override def onPush(): Unit = aggregateAndLoop()

        override def onUpstreamFinish(): Unit = {
          flush()
          super.onUpstreamFinish()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          flush()
          super.onUpstreamFailure(ex)
        }

      })

      setHandler(out, new OutHandler {

        // this callback is triggered after downstream pull requesting new emit
        // so the loop start from emit
        override def onPull(): Unit = emitAndLoop()

      })

      private def aggregateAndLoop(): Unit =
        while (aggregateAndReadyToEmit()
               && emitAndReadyToAggregate() // this make it ready to aggregate again
               ) {}

      private def emitAndLoop(): Unit =
        while (emitAndReadyToAggregate() // this make it ready to aggregate again
               && aggregateAndReadyToEmit()) {}

      private def emitAndReadyToAggregate(): Boolean =
        state.emitAttempt() && {
          // since the stage is ready to take new data again, signal upstream by pull
          // it might have already pulled if harvest is done by timer instead og aggregate
          if (!hasBeenPulled(in)) pull(in)
          true
        }

      private def aggregateAndReadyToEmit(): Boolean = {
        // loop until cannot aggregate anymore
        while (state.aggregateAttempt() &&
               // attempt harvest after every aggregation to check various conditions
               !state.harvestAttempt(mode = HarvestMode.AfterAggregation)) {}

        if (state.harvestedOutput.isEmpty) {
          // at the end of aggregation, if not harvested yet
          // schedule a gap timer to ensure aggregation time not exceeding the gap
          maxGap.foreach(mg => scheduleOnce(maxGapTimer, mg))
          false
        } else
          true
      }

      // force flush even condition not met
      private def flush(): Unit = state.harvestAttempt(mode = HarvestMode.Flush) && state.emitAttempt()

      // mutable state to keep track of the aggregator status to coordinate the flow
      // input/output handler callbacks are guaranteed to execute without concurrency
      // https://doc.akka.io/docs/akka/current/stream/stream-customize.html#thread-safety-of-custom-operators
      private val state = new State()

      /**
       * Three possible states
       * 1. no aggregator, at the beginning or after pushing to downstream
       * 2. has non-harvested aggregator
       * both 1 and 2 can aggregate input, but not ready to push output
       * 3. has harvested aggregator, must backpressure and wait for output port available
       * 1 -> 2 by aggregating new data
       * 2 -> 3 by aggregating or by timer data gap
       * 3 -> 1 by pushing to output port
       */
      class State {
        private var aggregator: Option[AggregatorState] = None

        private def aggregate(input: In): Unit =
          aggregator match {
            case Some(agg) => aggregator = Some(agg.aggregate(input))
            case None =>
              aggregator = Some(new AggregatorState(input))
              // schedule a timer for max duration
              maxDuration.foreach(md => scheduleOnce(maxDurationTimer, md))
          }

        // state transition to 3 if true
        def harvestAttempt(mode: HarvestMode.Value): Boolean =
          aggregator.map(_.harvest(mode)).getOrElse(false)

        // state transition 1 -> 2 or stay in 2
        def aggregateAttempt(): Boolean =
          isAvailable(in) && harvestedOutput.isEmpty && {
            val input = grab(in)
            aggregate(input)
            if (!hasBeenPulled(in)) pull(in) // always pull after grab
            true
          }

        // transition to state 1 if true
        def emitAttempt(): Boolean =
          isAvailable(out) &&
          harvestedOutput.exists { output =>
            push(out, output)
            aggregator = None
            true
          }

        def harvestedOutput: Option[Out] =
          aggregator.flatMap(agg => if (agg.hasBeenHarvested) Some(agg.out) else None)

      }

      object HarvestMode extends Enumeration {
        val AfterAggregation, OnTimer, Flush = Value
      }

      // expose interfaces for control
      class AggregatorState(input: In) {

        val startTime = System.currentTimeMillis()

        private var aggregator: Out = seed(input)
        def out: Out = aggregator

        private var lastAggregateTimeMs: Long = System.currentTimeMillis()

        private var harvested: Boolean = false // can be harvested by condition or timeout
        def hasBeenHarvested: Boolean = harvested

        def aggregate(input: In): AggregatorState = {
          // caller must check the condition before aggregating new input
          if (hasBeenHarvested) throw new Exception(s"cannot aggregate if already harvested")
          aggregator = Aggregator.this.aggregate(aggregator, input)
          lastAggregateTimeMs = System.currentTimeMillis()
          this
        }

        def harvest(mode: HarvestMode.Value): Boolean = {
          // this should not happen by checking the states on caller side
          if (harvested) {
            // do not harvest again
          } else if (mode == HarvestMode.Flush || emitReady(aggregator) || {
                       val currentTime = System.currentTimeMillis()
                       mode == HarvestMode.OnTimer && {
                         // check gap only on timer, not after aggregation
                         maxGap.exists(mg => currentTime - lastAggregateTimeMs >= mg.toMillis)
                       } || {
                         // check duration under all circumstances
                         maxDuration.exists(md => currentTime - startTime >= md.toMillis)
                       }
                     }) {
            harvested = true
            aggregator = Aggregator.this.harvest.map(h => h(aggregator)).getOrElse(aggregator)
          }
          harvested
        }

      }

    }

}
