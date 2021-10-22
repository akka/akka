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
 * @param seed initiate the aggregated output with first input
 * @param aggregate sequentially aggregate input into output
 * @param emitReady decide whether the current aggregated output can be emitted
 * @param maxGap maximum allowed gap between aggregations, will emit if the interval passed this threshold
 * @param maxDuration optional total duration for the entire aggregator, requiring a separate timer.
 * @param harvest  this is invoked as soon as all conditions are met before emitting to next stage, which could take more time.
 *                 time sensitive operations can be added here, such as closing an output channel
 * @tparam In
 * @tparam Agg
 */
class Aggregator[In, Agg, Out](
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
    new TimerGraphStageLogic(shape) {
      override def preStart() = {
        pull(in)
      }

      // there are two timers one for gap one for total duration
      // this can potentially harvest the current aggregator and start the loop from emit
      override protected def onTimer(timerKey: Any): Unit = {
        //println(s"onTimer $timerKey begin state=$state")
        if (state.harvestOnTimer()) state.emitAndLoop()
        //println(s"onTimer $timerKey end state=$state")
      }

      setHandler(in, new InHandler {

        // this callback is triggered after upstream push with new data
        // so the loop start from aggregate
        override def onPush(): Unit = {
          //println(s"onPush begin state=$state")
          state.aggregateAndLoop()
          state.pullIfNoBackPressure()
          //println(s"onPush end state=$state")
        }

        override def onUpstreamFinish(): Unit = {
          //println(s"onFinish begin state=$state")
          state.flush()
          //println(s"onFinish end state=$state")
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          failStage(ex)
        }

      })

      setHandler(out, new OutHandler {

        // this callback is triggered after downstream pull requesting new emit
        // so the loop start from emit
        override def onPull(): Unit = {
          state.hasScheduledEmit = false
          //println(s"onPull begin state=$state")
          state.aggregateAndLoop()
          state.pullIfNoBackPressure()
          //println(s"onPull end state=$state")
        }

      })

      // mutable state to keep track of the aggregator status to coordinate the flow
      // input/output handler callbacks are guaranteed to execute without concurrency
      // https://doc.akka.io/docs/akka/current/stream/stream-customize.html#thread-safety-of-custom-operators
      private val state = new State()

      /**
       * Three possible states
       * 1. no aggregator, no pendingEmit, happens at the beginning or after pushing to downstream
       * 2. has aggregator, but no pendingEmit
       * both 1 and 2 can aggregate input, but not ready to push output
       * 3. has pendingEmit, no aggregators, must backpressure (not call pull) and wait for output port available
       * 1 -> 2 by aggregating new data
       * 2 -> 3 by aggregating or by timer
       * 3 -> 1 by emitting to output port
       */
      class State {
        override def toString: String = s"State[agg=$aggregator,emit=$pendingEmit, pending=$hasScheduledEmit]"
        private var aggregator: Option[AggregatorState] = None
        private var pendingEmit: Option[Out] = None

        private def aggregate(input: In): Unit = {
          aggregator match {
            case Some(agg) =>
              aggregator = Some(agg.aggregate(input))
            case None =>
              aggregator = Some(new AggregatorState(input))
              // schedule a timer for max duration
              maxDuration.foreach(md => scheduleOnce(maxDurationTimer, md))
          }
          // this will close the aggregator right away if emitReady
          harvestAttempt(HarvestMode.AfterAggregation)
        }

        def harvestOnTimer(): Boolean = harvestAttempt(mode = HarvestMode.OnTimer)
        // state transition to 3 if true
        private def harvestAttempt(mode: HarvestMode.Value): Boolean = pendingEmit.isEmpty && {
          aggregator match {
            case Some(agg) =>
              if (mode == HarvestMode.Flush || emitReady(agg.aggregator) || {
                val currentTime = System.currentTimeMillis()
                mode == HarvestMode.OnTimer && {
                  // check gap only on timer, not after aggregation
                  maxGap.exists(mg => currentTime - agg.lastAggregateTimeMs >= mg.toMillis)
                } || {
                  // check duration under all circumstances
                  maxDuration.exists(md => currentTime - agg.startTime >= md.toMillis)
                }
              }) {
                // set to None
                aggregator = None
                pendingEmit = Some(Aggregator.this.harvest(agg.aggregator))
              }
              pendingEmit.nonEmpty
            case None => false
          }

        }

        // state transition 1 -> 2 or stay in 2
        private def aggregateAttempt(): Boolean =
          isAvailable(in) && {
            // always grab after checking available or it will get lost on next callback
            // this is not common sense understanding
            val input = grab(in)
            //println(s"aggregating $input")
            aggregate(input)
            //println(s"after aggregate $this")
            true
          }

        var hasScheduledEmit = false
        // transition to state 1 if true
        private def emitRightAway(): Boolean = {
          val result =
          !pendingEmit.exists { output =>
            //println(s"emit $output")
            hasScheduledEmit = !isAvailable(out)
            emit(out, output) // if out port not available, it'll follow up as scheduled emit
            pendingEmit = None
            hasScheduledEmit
          }
          //println(s"no backpressure $result $state")
          result
        }

        def pullIfNoBackPressure(): Unit = {
          if (!hasScheduledEmit && !hasBeenPulled(in)) { // pull at the end of loop is there is no hanging emit
            //println(s"pulling")
            pull(in)
          }
        }

        def aggregateAndLoop(): Unit =
        {
          while (aggregateAndReadyToEmit()
            && emitRightAway() // this make it ready to aggregate again
          ) {}
        }

        def emitAndLoop(): Unit = {
          while (emitRightAway() // this make it ready to aggregate again
            && aggregateAndReadyToEmit()) {}
        }

        def aggregateAndReadyToEmit(): Boolean = {
          // loop until cannot aggregate anymore
          //println(s"agg attempt begin $state")
          while (aggregateAttempt() &&
            // attempt harvest after every aggregation to check various conditions
            pendingEmit.isEmpty) {}

          //println(s"agg attempt end $state")
          if (aggregator.nonEmpty) {
            // if it's still aggregating
            // schedule a gap timer to ensure aggregation time not exceeding the gap
            maxGap.foreach(mg => scheduleOnce(maxGapTimer, mg))
          }
          pendingEmit.nonEmpty
        }

        // force flush even condition not met
        def flush(): Unit = {
          if (harvestAttempt(mode = HarvestMode.Flush)) emitRightAway()
        }

      }

      object HarvestMode extends Enumeration {
        val AfterAggregation, OnTimer, Flush = Value
      }

      // expose interfaces for control
      class AggregatorState(input: In) {
        override def toString: String = s"agg=${aggregator}"
        val startTime = System.currentTimeMillis()

        var aggregator: Agg = seed(input)

        var lastAggregateTimeMs: Long = System.currentTimeMillis()

        def aggregate(input: In): AggregatorState = {
          aggregator = Aggregator.this.aggregate(aggregator, input)
          lastAggregateTimeMs = System.currentTimeMillis()
          this
        }

      }

    }

}