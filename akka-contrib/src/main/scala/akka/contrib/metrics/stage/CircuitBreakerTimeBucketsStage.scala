package akka.contrib.metrics.stage

import akka.Done
import akka.contrib.metrics.Event.{ BreakerClosed, BreakerHalfOpened, BreakerOpened }
import akka.contrib.metrics.{ CountBucket, Event, TimeBucket, TimeBucketResult }
import akka.pattern.CircuitBreaker
import akka.stream._
import akka.stream.impl.Buffer
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogic }

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

private[metrics] class CircuitBreakerTimeBucketsStage(breaker: CircuitBreaker, interval: FiniteDuration, maxBuffer: Int)
  extends GraphStage[SourceShape[TimeBucketResult]] {

  private val out = Outlet[TimeBucketResult]("CircuitBreakerTimeBucketsStage.out")

  override def shape: SourceShape[TimeBucketResult] = SourceShape.of(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with OutHandler {
    var buffer: Buffer[TimeBucketResult] = _
    var state: TimeBucketResultHelper = _

    override def preStart(): Unit = {
      buffer = Buffer(maxBuffer, materializer)
      state = new TimeBucketResultHelper(now)

      schedulePeriodically(Done, interval)

      breaker.onOpen(callback.invoke(Event.BreakerOpened(now)))
      breaker.onHalfOpen(callback.invoke(Event.BreakerHalfOpened(now)))
      breaker.onClose(callback.invoke(Event.BreakerClosed(now)))

      breaker.onCallSuccess(e ⇒ callback.invoke(Event.CallSuccess(now, e / 1000)))
      breaker.onCallFailure(e ⇒ callback.invoke(Event.CallFailure(now, e / 1000)))
      breaker.onCallTimeout(e ⇒ callback.invoke(Event.CallTimeout(now, e / 1000)))
      breaker.onCallBreakerOpen(callback.invoke(Event.CallBreakerOpen(now)))
    }

    private def now = System.currentTimeMillis()

    private val callback = getAsyncCallback[Event] {
      case e: Event.BreakerOpened     ⇒ state.opens += e
      case e: Event.BreakerHalfOpened ⇒ state.halfOpens += e
      case e: Event.BreakerClosed     ⇒ state.closes += e

      case e: Event.CallSuccess       ⇒ state.callSuccesses.append(e.elapsed)
      case e: Event.CallFailure       ⇒ state.callFailures.append(e.elapsed)
      case e: Event.CallTimeout       ⇒ state.callTimeouts.append(e.elapsed)
      case _: Event.CallBreakerOpen   ⇒ state.callBreakerOpens += 1
    }

    override def onPull(): Unit =
      if (buffer.nonEmpty) push(out, buffer.dequeue())

    override protected def onTimer(timerKey: Any): Unit = {
      if (buffer.isFull) buffer.dropHead()
      val time = now
      val bucket = state.toBucket(time)

      if (buffer.isEmpty && isAvailable(out)) {
        push(out, bucket)
      } else {
        buffer.enqueue(bucket)
      }

      state.reset(time + 1)
    }

    setHandler(out, this)
  }

  class TimeBucketResultHelper(var start: Long) {
    val opens: mutable.ListBuffer[BreakerOpened] = mutable.ListBuffer.empty
    val closes: mutable.ListBuffer[BreakerClosed] = mutable.ListBuffer.empty
    val halfOpens: mutable.ListBuffer[BreakerHalfOpened] = mutable.ListBuffer.empty
    val callSuccesses: TimeBucketHelper = new TimeBucketHelper
    val callFailures: TimeBucketHelper = new TimeBucketHelper
    val callTimeouts: TimeBucketHelper = new TimeBucketHelper
    var callBreakerOpens: Long = 0

    def toBucket(stop: Long) = TimeBucketResult(start, stop, opens.toList, closes.toList, halfOpens.toList,
      callSuccesses.toReal, callFailures.toReal, callTimeouts.toReal, CountBucket(callBreakerOpens))

    def reset(start: Long) = {
      opens.clear()
      closes.clear()
      halfOpens.clear()
      callSuccesses.reset()
      callFailures.reset()
      callTimeouts.reset()
      callBreakerOpens = 0
    }
  }

  class TimeBucketHelper {
    var elapsedTotal: Long = 0
    var count: Long = 0

    def reset() = {
      elapsedTotal = 0
      count = 0
    }

    def append(elapsed: Long) = {
      elapsedTotal += elapsed
      count += 1
    }

    def toReal = TimeBucket(elapsedTotal, count)
  }

}
