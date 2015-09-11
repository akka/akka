package akka.stream.io

import java.util.concurrent.{ TimeUnit, TimeoutException }

import akka.actor.{ Cancellable, ActorSystem }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.{ FlowShape, Outlet, Inlet, BidiShape }
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }

import scala.concurrent.duration.{ Deadline, FiniteDuration }

/**
 * Various stages for controlling timeouts on IO related streams (although not necessarily).
 *
 * The common theme among the processing stages here that
 *  - they wait for certain event or events to happen
 *  - they have a timer that may fire before these events
 *  - if the timer fires before the event happens, these stages all fail the stream
 *  - otherwise, these streams do not interfere with the element flow, ordinary completion or failure
 */
object Timeouts {

  /**
   * If the first element has not passed through this stage before the provided timeout, the stream is failed
   * with a [[TimeoutException]].
   */
  def initalTimeout[T](timeout: FiniteDuration): Flow[T, T, Unit] =
    Flow.wrap(new InitialTimeout[T](timeout))

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[TimeoutException]].
   */
  def completionTimeout[T](timeout: FiniteDuration): Flow[T, T, Unit] =
    Flow.wrap(new CompletionTimeout[T](timeout))

  /**
   * If the time between two processed elements exceed the provided timeout, the stream is failed
   * with a [[TimeoutException]].
   */
  def idleTimeout[T](timeout: FiniteDuration): Flow[T, T, Unit] =
    Flow.wrap(new IdleTimeout[T](timeout))

  /**
   * If the time between two processed elements *in any direction* exceed the provided timeout, the stream is failed
   * with a [[TimeoutException]].
   *
   * There is a difference between this stage and having two idleTimeout Flows assembled into a BidiStage.
   * If the timout is configured to be 1 seconds, then this stage will not fail even though there are elements flowing
   * every second in one direction, but no elements are flowing in the other direction. I.e. this stage considers
   * the *joint* frequencies of the elements in both directions.
   */
  def idleTimeoutBidi[A, B](timeout: FiniteDuration): BidiFlow[A, A, B, B, Unit] =
    BidiFlow.wrap(new IdleTimeoutBidi[A, B](timeout))

  private def idleTimeoutCheckInterval(timeout: FiniteDuration): FiniteDuration = {
    import scala.concurrent.duration._
    FiniteDuration(
      math.min(math.max(timeout.toNanos / 8, 100.millis.toNanos), timeout.toNanos / 2),
      TimeUnit.NANOSECONDS)
  }

  private class InitialTimeout[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

    override def createLogic: GraphStageLogic = new SimpleLinearStageLogic {
      private var initialHasPassed = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          initialHasPassed = true
          push(out, grab(in))
        }
      })

      final override protected def onTimer(key: Any): Unit =
        if (!initialHasPassed)
          failStage(new TimeoutException(s"The first element has not yet passed through in $timeout."))

      scheduleOnce("InitialTimeout", timeout)
    }

    override def toString = "InitialTimeoutTimer"
  }

  private class CompletionTimeout[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

    override def createLogic: GraphStageLogic = new SimpleLinearStageLogic {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
      })

      final override protected def onTimer(key: Any): Unit =
        failStage(new TimeoutException(s"The stream has not been completed in $timeout."))

      scheduleOnce("CompletionTimeoutTimer", timeout)
    }

    override def toString = "CompletionTimeout"
  }

  private class IdleTimeout[T](timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {
    private var nextDeadline: Deadline = Deadline.now + timeout

    override def createLogic: GraphStageLogic = new SimpleLinearStageLogic {
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          nextDeadline = Deadline.now + timeout
          push(out, grab(in))
        }
      })

      final override protected def onTimer(key: Any): Unit =
        if (nextDeadline.isOverdue())
          failStage(new TimeoutException(s"No elements passed in the last $timeout."))

      schedulePeriodically("IdleTimeoutCheckTimer", interval = idleTimeoutCheckInterval(timeout))
    }

    override def toString = "IdleTimeout"
  }

  private class IdleTimeoutBidi[I, O](val timeout: FiniteDuration) extends GraphStage[BidiShape[I, I, O, O]] {
    val in1 = Inlet[I]("in1")
    val in2 = Inlet[O]("in2")
    val out1 = Outlet[I]("out1")
    val out2 = Outlet[O]("out2")
    val shape = BidiShape(in1, out1, in2, out2)

    override def toString = "IdleTimeoutBidi"

    override def createLogic: GraphStageLogic = new GraphStageLogic {
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

      schedulePeriodically("IdleTimeoutCheckTimer", idleTimeoutCheckInterval(timeout))
    }
  }

}
