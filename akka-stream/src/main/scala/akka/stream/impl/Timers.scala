/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.concurrent.{ TimeUnit, TimeoutException }

import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }

import scala.concurrent.duration.{ Duration, Deadline, FiniteDuration }

/**
 * INTERNAL API
 *
 * Various stages for controlling timeouts on IO related streams (although not necessarily).
 *
 * The common theme among the processing stages here that
 *  - they wait for certain event or events to happen
 *  - they have a timer that may fire before these events
 *  - if the timer fires before the event happens, these stages all fail the stream
 *  - otherwise, these streams do not interfere with the element flow, ordinary completion or failure
 */
private[stream] object Timers {
  private def idleTimeoutCheckInterval(timeout: FiniteDuration): FiniteDuration = {
    import scala.concurrent.duration._
    FiniteDuration(
      math.min(math.max(timeout.toNanos / 8, 100.millis.toNanos), timeout.toNanos / 2),
      TimeUnit.NANOSECONDS)
  }

  final class Initial[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private var initialHasPassed = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          initialHasPassed = true
          push(out, grab(in))
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })

      final override protected def onTimer(key: Any): Unit =
        if (!initialHasPassed)
          failStage(new TimeoutException(s"The first element has not yet passed through in $timeout."))

      override def preStart(): Unit = scheduleOnce("InitialTimeout", timeout)
    }

    override def toString = "InitialTimeoutTimer"
  }

  final class Completion[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })

      final override protected def onTimer(key: Any): Unit =
        failStage(new TimeoutException(s"The stream has not been completed in $timeout."))

      override def preStart(): Unit = scheduleOnce("CompletionTimeoutTimer", timeout)
    }

    override def toString = "CompletionTimeout"
  }

  final class Idle[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private var nextDeadline: Deadline = Deadline.now + timeout
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          nextDeadline = Deadline.now + timeout
          push(out, grab(in))
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })

      final override protected def onTimer(key: Any): Unit =
        if (nextDeadline.isOverdue())
          failStage(new TimeoutException(s"No elements passed in the last $timeout."))

      override def preStart(): Unit = schedulePeriodically("IdleTimeoutCheckTimer", interval = idleTimeoutCheckInterval(timeout))
    }

    override def toString = "IdleTimeout"
  }

  final class IdleTimeoutBidi[I, O](val timeout: FiniteDuration) extends GraphStage[BidiShape[I, I, O, O]] {
    val in1 = Inlet[I]("in1")
    val in2 = Inlet[O]("in2")
    val out1 = Outlet[I]("out1")
    val out2 = Outlet[O]("out2")

    override def initialAttributes = Attributes.name("IdleTimeoutBidi")
    val shape = BidiShape(in1, out1, in2, out2)

    override def toString = "IdleTimeoutBidi"

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private var nextDeadline: Deadline = Deadline.now + timeout

      setHandler(in1, new InHandler {
        override def onPush(): Unit = {
          onActivity()
          push(out1, grab(in1))
        }
        override def onUpstreamFinish(): Unit = complete(out1)
      })

      setHandler(in2, new InHandler {
        override def onPush(): Unit = {
          onActivity()
          push(out2, grab(in2))
        }
        override def onUpstreamFinish(): Unit = complete(out2)
      })

      setHandler(out1, new OutHandler {
        override def onPull(): Unit = pull(in1)
        override def onDownstreamFinish(): Unit = cancel(in1)
      })

      setHandler(out2, new OutHandler {
        override def onPull(): Unit = pull(in2)
        override def onDownstreamFinish(): Unit = cancel(in2)
      })

      private def onActivity(): Unit = nextDeadline = Deadline.now + timeout

      final override def onTimer(key: Any): Unit =
        if (nextDeadline.isOverdue())
          failStage(new TimeoutException(s"No elements passed in the last $timeout."))

      override def preStart(): Unit = schedulePeriodically("IdleTimeoutCheckTimer", idleTimeoutCheckInterval(timeout))
    }
  }

  final class DelayInitial[T](val delay: FiniteDuration) extends GraphStage[FlowShape[T, T]] {
    val in: Inlet[T] = Inlet("IdleInject.in")
    val out: Outlet[T] = Outlet("IdleInject.out")
    override def initialAttributes = Attributes.name("DelayInitial")
    override val shape: FlowShape[T, T] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private val IdleTimer = "DelayTimer"

      override def preStart(): Unit = {
        if (delay == Duration.Zero) open = true
        else scheduleOnce(IdleTimer, delay)
      }

      private var open: Boolean = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = if (open) pull(in)
      })

      override protected def onTimer(timerKey: Any): Unit = {
        open = true
        if (isAvailable(out)) pull(in)
      }
    }
  }

  final class IdleInject[I, O >: I](val timeout: FiniteDuration, inject: () ⇒ O) extends GraphStage[FlowShape[I, O]] {
    val in: Inlet[I] = Inlet("IdleInject.in")
    val out: Outlet[O] = Outlet("IdleInject.out")
    override def initialAttributes = Attributes.name("IdleInject")
    override val shape: FlowShape[I, O] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private val IdleTimer = "IdleTimer"
      private var nextDeadline = Deadline.now + timeout

      // Prefetching to ensure priority of actual upstream elements
      override def preStart(): Unit = pull(in)

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          nextDeadline = Deadline.now + timeout
          cancelTimer(IdleTimer)
          if (isAvailable(out)) {
            push(out, grab(in))
            pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (!isAvailable(in)) completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (isAvailable(in)) {
            push(out, grab(in))
            if (isClosed(in)) completeStage()
            else pull(in)
          } else {
            if (nextDeadline.isOverdue()) {
              nextDeadline = Deadline.now + timeout
              push(out, inject())
            } else scheduleOnce(IdleTimer, nextDeadline.timeLeft)
          }
        }
      })

      override protected def onTimer(timerKey: Any): Unit = {
        if (nextDeadline.isOverdue() && isAvailable(out)) {
          push(out, inject())
          nextDeadline = Deadline.now + timeout
        }
      }
    }

  }

}
