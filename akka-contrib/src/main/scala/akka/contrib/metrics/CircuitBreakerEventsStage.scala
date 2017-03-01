package akka.contrib.metrics

import akka.pattern.CircuitBreaker
import akka.stream.OverflowStrategies._
import akka.stream._
import akka.stream.impl.Buffer
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}


private[metrics] class CircuitBreakerEventsStage(breaker: CircuitBreaker, maxBuffer: Int, overflowStrategy: OverflowStrategy) extends GraphStage[SourceShape[Event]] {
  private val out = Outlet[Event]("CircuitBreakerEventsStage.out")

  override def shape: SourceShape[Event] = SourceShape.of(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {
    var buffer: Buffer[Event] = _

    override def preStart(): Unit = {
      if (maxBuffer > 0) buffer = Buffer(maxBuffer, materializer)

      breaker.onOpen(callback.invoke(Event.BreakerOpened(now)))
      breaker.onHalfOpen(callback.invoke(Event.BreakerHalfOpened(now)))
      breaker.onClose(callback.invoke(Event.BreakerOpened(now)))

      breaker.onCallSuccess(e ⇒ callback.invoke(Event.CallSuccess(now, e)))
      breaker.onCallFailure(e ⇒ callback.invoke(Event.CallFailure(now, e)))
      breaker.onCallTimeout(e ⇒ callback.invoke(Event.CallTimeout(now, e)))
      breaker.onCallBreakerOpen(callback.invoke(Event.CallBreakerOpen(now)))
    }

    private def now = System.currentTimeMillis()

    private def bufferElem(event: Event): Unit = {
      if (!buffer.isFull) {
        buffer.enqueue(event)
      } else overflowStrategy match {
        case DropHead ⇒
          buffer.dropHead()
          buffer.enqueue(event)
        case DropTail ⇒
          buffer.dropTail()
          buffer.enqueue(event)
        case DropBuffer ⇒
          buffer.clear()
          buffer.enqueue(event)
        case DropNew ⇒
        //do nothing
        case Fail ⇒
          failStage(BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!"))
        case Backpressure ⇒
          failStage(new IllegalStateException("CircuitBreakerEventsStage buffer does not support Backpressure"))
      }
    }

    private val callback = getAsyncCallback[Event] { event ⇒
      if (maxBuffer != 0) {
        bufferElem(event)
        if (isAvailable(out)) push(out, buffer.dequeue())
      } else if (isAvailable(out)) {
        push(out, event)
      }

    }


    override def onPull(): Unit =
      if (maxBuffer != 0 && buffer.nonEmpty) {
        push(out, buffer.dequeue())
      }

    setHandler(out, this)
  }
}
