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
        println("onTimer")
        if (state.harvestAttempt(mode = HarvestMode.OnTimer)) emitAndLoop()
      }

      setHandler(in, new InHandler {

        // this callback is triggered after upstream push with new data
        // so the loop start from aggregate
        override def onPush(): Unit = {
          println("onPush")
          aggregateAndLoop()
        }

        override def onUpstreamFinish(): Unit = {
          println("upstream finish")
          flush()
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          println("upstream failure")
          failStage(ex)
        }


      })

      setHandler(out, new OutHandler {

        // this callback is triggered after downstream pull requesting new emit
        // so the loop start from emit
        override def onPull(): Unit = {
          println(s"onPull ${state}")
          emitAndLoop()
        }

      })

      private def aggregateAndLoop(): Unit =
        while (aggregateAndReadyToEmit()
               && emitAndReadyToAggregate() // this make it ready to aggregate again
               ) {}

      private def emitAndLoop(): Unit =
        while (emitAndReadyToAggregate() // this make it ready to aggregate again
               && aggregateAndReadyToEmit()) {}

      private def emitAndReadyToAggregate(): Boolean = {
        state.emitAttempt() && {
          // since the stage is ready to take new data again, signal upstream by pull
          // it might have already pulled if harvest is done by timer instead og aggregate
          if (!hasBeenPulled(in)) {
            // pull is not necessary during onPush
            // call pull in onPull is sufficient, but it does not hurt to generate a pull event to always keep the stream energized
            println("pulling after emit")
            pull(in)
          }
          true
        }
      }

      private def aggregateAndReadyToEmit(): Boolean = {
        // loop until cannot aggregate anymore
        while (state.aggregateAttempt() &&
               // attempt harvest after every aggregation to check various conditions
               state.pendingOutput.isEmpty) {}

        if (state.pendingOutput.isEmpty) {
          // at the end of aggregation, if not harvested yet
          // schedule a gap timer to ensure aggregation time not exceeding the gap
          maxGap.foreach(mg => scheduleOnce(maxGapTimer, mg))
          println("not ready to emit")
          false
        } else {
            println("ready to emit")
            true
          }
      }

      // force flush even condition not met
      private def flush(): Unit = {
        println(s"flushing state=$state")
        state.harvestAttempt(mode = HarvestMode.Flush) && {
          state.pendingOutput.exists { output =>
            println(s"state=$state, final emit $output")
            emit(out, output, () => completeStage()) // make sure complete happens after flushing
            true
          }
        }
      }

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
        override def toString: String = s"State[aggregator=${
          val str = aggregator.toString
          s"${str.take(100)}${if(str.length >100) s"(${str.length})" else ""}"
        }, pending=${
          val str = pendingEmit.toString
          s"${str.take(100)}${if(str.length >100) s"(${str.length})" else ""}"
        }]"
        var aggregator: Option[AggregatorState] = None
        private var pendingEmit: Option[Out] = None

        private def aggregate(input: In): Unit = {
          println(s"aggregating $input")
          aggregator match {
            case Some(agg) =>
              // as long as the stage logic does not pull when not ready, this should not happen
              if (emitReady(agg.aggregator))
                throw new Exception(s"already emitReady, cannot aggregate more: $agg, $input")
              aggregator = Some(agg.aggregate(input)  )
            case None =>
              aggregator = Some(new AggregatorState(input))
              // schedule a timer for max duration
              maxDuration.foreach(md => scheduleOnce(maxDurationTimer, md))
          }
        }

        // state transition to 3 if true
        def harvestAttempt(mode: HarvestMode.Value): Boolean = pendingEmit.isEmpty && {
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
        def aggregateAttempt(): Boolean =
          {if (isAvailable(in)){
            println(s"input is available $state")
            true} else {
            println(s"input not available $state")
            false}} && {
            // always grab after checking available or it will get lost on next callback
            // this is not common sense understanding
            val input = grab(in)
            println(s"grabbed $input")
            aggregate(input)
            if (pendingOutput.isEmpty) { // must guarantee pendingOutput is empty before pulling
              if (!hasBeenPulled(in)) {
                println("pulling after aggregate")
                pull(in)
              }
            }
            state.harvestAttempt(HarvestMode.AfterAggregation)
            println(s"state=$state")

            true
          }

        // transition to state 1 if true
        def emitAttempt(): Boolean =
          pendingOutput.exists { output =>
            {if(isAvailable(out)){
              println("out port is available, emit")
              push(out, output)
              pendingEmit = None
              true
            } else {
              println("out port not available")
              emit(out, output) // follow up emit
              pendingEmit = None
              false // this will not pull and no more onPush to prevent from adding too much followup emit
            }
            }
          }

        def pendingOutput: Option[Out] = pendingEmit

      }

      object HarvestMode extends Enumeration {
        val AfterAggregation, OnTimer, Flush = Value
      }

      // expose interfaces for control
      class AggregatorState(input: In) {

        override def toString: String = s"aggregator=${aggregator}"
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

/*
[error] 	akka.stream.scaladsl.SourceWithContextSpec // passed, ok failure with pending emit but unavailable out port
[error] 	akka.stream.scaladsl.FlowGroupedWeightedSpec // passed, ok failure with pending emit but unavailable out port
[error] 	akka.stream.impl.fusing.InterpreterSupervisionSpec // passed after modification
[error] 	akka.stream.impl.fusing.InterpreterSpec // passed after modification
[error] 	akka.stream.scaladsl.FramingSpec // passed
[error] 	akka.stream.scaladsl.FlowJoinSpec // passed
[error] 	akka.stream.scaladsl.FlowLimitWeightedSpec // passed
[error] 	akka.stream.scaladsl.FlowLimitSpec // passed
[error] 	akka.stream.scaladsl.WithContextUsageSpec // ok failure, the right elements are ready to emit but out port not available due to the test setup
[error] 	akka.stream.javadsl.SourceTest // timeout
[error] 	akka.stream.scaladsl.BoundedSourceQueueSpec
[error] 	akka.stream.scaladsl.GraphMergePreferredSpec

[error] 	akka.stream.scaladsl.FlowGroupedWithinSpec
 */