/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import java.util.concurrent.TimeUnit.NANOSECONDS

import akka.actor.{ ActorRef, Terminated }
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.dispatch.ExecutionContexts
import akka.event.Logging.LogLevel
import akka.event.{ LogSource, Logging, LoggingAdapter }
import akka.stream.Attributes.{ InputBuffer, LogLevels }
import akka.stream.OverflowStrategies._
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.impl.{ ReactiveStreamsCompliance, Buffer ⇒ BufferImpl }
import akka.stream.scaladsl.{ Flow, Keep, Source }
import akka.stream.stage._
import akka.stream.{ Supervision, _ }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import scala.concurrent.{ Future, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success, Try }

import akka.stream.ActorAttributes.SupervisionStrategy
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.control.Exception.Catcher

import akka.stream.impl.Stages.DefaultAttributes
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Map[In, Out](f: In ⇒ Out) extends GraphStage[FlowShape[In, Out]] {
  val in = Inlet[In]("Map.in")
  val out = Outlet[Out]("Map.out")
  override val shape = FlowShape(in, out)

  override def initialAttributes: Attributes = DefaultAttributes.map

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private def decider =
        inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      override def onPush(): Unit = {
        try {
          push(out, f(grab(in)))
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case _                ⇒ pull(in)
          }
        }
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Filter[T](p: T ⇒ Boolean) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.filter

  override def toString: String = "Filter"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler with InHandler {
      def decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      override def onPush(): Unit = {
        try {
          val elem = grab(in)
          if (p(elem)) {
            push(out, elem)
          } else {
            pull(in)
          }
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case _                ⇒ pull(in)
          }
        }
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class TakeWhile[T](p: T ⇒ Boolean, inclusive: Boolean = false) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.takeWhile

  override def toString: String = "TakeWhile"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler with InHandler {
      override def toString = "TakeWhileLogic"

      def decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      override def onPush(): Unit = {
        try {
          val elem = grab(in)
          if (p(elem)) {
            push(out, elem)
          } else {
            if (inclusive) push(out, elem)
            completeStage()
          }
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case _                ⇒ pull(in)
          }
        }
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class DropWhile[T](p: T ⇒ Boolean) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.dropWhile

  def createLogic(inheritedAttributes: Attributes) = new SupervisedGraphStageLogic(inheritedAttributes, shape) with InHandler with OutHandler {
    override def onPush(): Unit = {
      val elem = grab(in)
      withSupervision(() ⇒ p(elem)) match {
        case Some(flag) if flag ⇒ pull(in)
        case Some(flag) if !flag ⇒
          push(out, elem)
          setHandler(in, rest)
        case None ⇒ // do nothing
      }
    }

    def rest = new InHandler {
      def onPush() = push(out, grab(in))
    }

    override def onResume(t: Throwable): Unit = if (!hasBeenPulled(in)) pull(in)

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

  override def toString = "DropWhile"
}

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] abstract class SupervisedGraphStageLogic(inheritedAttributes: Attributes, shape: Shape) extends GraphStageLogic(shape) {
  private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

  def withSupervision[T](f: () ⇒ T): Option[T] =
    try {
      Some(f())
    } catch {
      case NonFatal(ex) ⇒
        decider(ex) match {
          case Supervision.Stop    ⇒ onStop(ex)
          case Supervision.Resume  ⇒ onResume(ex)
          case Supervision.Restart ⇒ onRestart(ex)
        }
        None
    }

  def onResume(t: Throwable): Unit

  def onStop(t: Throwable): Unit = failStage(t)

  def onRestart(t: Throwable): Unit = onResume(t)
}

private[stream] object Collect {
  // Cached function that can be used with PartialFunction.applyOrElse to ensure that A) the guard is only applied once,
  // and the caller can check the returned value with Collect.notApplied to query whether the PF was applied or not.
  // Prior art: https://github.com/scala/scala/blob/v2.11.4/src/library/scala/collection/immutable/List.scala#L458
  final val NotApplied: Any ⇒ Any = _ ⇒ Collect.NotApplied
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Collect[In, Out](pf: PartialFunction[In, Out]) extends GraphStage[FlowShape[In, Out]] {
  val in = Inlet[In]("Collect.in")
  val out = Outlet[Out]("Collect.out")
  override val shape = FlowShape(in, out)

  override def initialAttributes: Attributes = DefaultAttributes.collect

  def createLogic(inheritedAttributes: Attributes) = new SupervisedGraphStageLogic(inheritedAttributes, shape) with InHandler with OutHandler {

    import Collect.NotApplied

    val wrappedPf = () ⇒ pf.applyOrElse(grab(in), NotApplied)

    override def onPush(): Unit = withSupervision(wrappedPf) match {
      case Some(result) ⇒ result match {
        case NotApplied             ⇒ pull(in)
        case result: Out @unchecked ⇒ push(out, result)
      }
      case None ⇒ //do nothing
    }

    override def onResume(t: Throwable): Unit = if (!hasBeenPulled(in)) pull(in)

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

  override def toString = "Collect"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Recover[T](pf: PartialFunction[Throwable, T]) extends SimpleLinearGraphStage[T] {
  override protected def initialAttributes: Attributes = DefaultAttributes.recover

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

    import Collect.NotApplied

    var recovered: Option[T] = None

    override def onPush(): Unit = {
      push(out, grab(in))
    }

    override def onPull(): Unit = {
      recovered match {
        case Some(elem) ⇒
          push(out, elem)
          completeStage()
        case None ⇒
          pull(in)
      }
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      pf.applyOrElse(ex, NotApplied) match {
        case NotApplied ⇒ failStage(ex)
        case result: T @unchecked ⇒ {
          if (isAvailable(out)) {
            push(out, result)
            completeStage()
          } else {
            recovered = Some(result)
          }
        }
      }
    }

    setHandlers(in, out, this)
  }
}

/**
 * Maps error with the provided function if it is defined for an error or, otherwise, passes it on unchanged.
 *
 * While similar to [[Recover]] this operator can be used to transform an error signal to a different one *without* logging
 * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
 * would log the `t2` error.
 */
@InternalApi private[akka] final case class MapError[T](f: PartialFunction[Throwable, Throwable]) extends SimpleLinearGraphStage[T] {
  override def createLogic(attr: Attributes) =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      override def onPush(): Unit = push(out, grab(in))

      override def onUpstreamFailure(ex: Throwable): Unit =
        if (f.isDefinedAt(ex)) super.onUpstreamFailure(f(ex))
        else super.onUpstreamFailure(ex)

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Take[T](count: Long) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.take

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var left: Long = count

    override def onPush(): Unit = {
      if (left > 0) {
        push(out, grab(in))
        left -= 1
      }
      if (left <= 0) completeStage()
    }

    override def onPull(): Unit = {
      if (left > 0) pull(in)
      else completeStage()
    }

    setHandlers(in, out, this)
  }

  override def toString: String = "Take"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Drop[T](count: Long) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.drop

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var left: Long = count

    override def onPush(): Unit = {
      if (left > 0) {
        left -= 1
        pull(in)
      } else push(out, grab(in))
    }

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

  override def toString: String = "Drop"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Scan[In, Out](zero: Out, f: (Out, In) ⇒ Out) extends GraphStage[FlowShape[In, Out]] {
  override val shape = FlowShape[In, Out](Inlet("Scan.in"), Outlet("Scan.out"))

  override def initialAttributes: Attributes = DefaultAttributes.scan

  override def toString: String = "Scan"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler { self ⇒

      private var aggregator = zero
      private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      import Supervision.{ Stop, Resume, Restart }
      import shape.{ in, out }

      // Initial behavior makes sure that the zero gets flushed if upstream is empty
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          push(out, aggregator)
          setHandlers(in, out, self)
        }
      })

      setHandler(in, new InHandler {
        override def onPush(): Unit = ()

        override def onUpstreamFinish(): Unit = setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            push(out, aggregator)
            completeStage()
          }
        })
      })

      override def onPull(): Unit = pull(in)

      override def onPush(): Unit = {
        try {
          aggregator = f(aggregator, grab(in))
          push(out, aggregator)
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Resume ⇒ if (!hasBeenPulled(in)) pull(in)
            case Stop   ⇒ failStage(ex)
            case Restart ⇒
              aggregator = zero
              push(out, aggregator)
          }
        }
      }
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class ScanAsync[In, Out](zero: Out, f: (Out, In) ⇒ Future[Out]) extends GraphStage[FlowShape[In, Out]] {

  import akka.dispatch.ExecutionContexts

  val in = Inlet[In]("ScanAsync.in")
  val out = Outlet[Out]("ScanAsync.out")
  override val shape: FlowShape[In, Out] = FlowShape[In, Out](in, out)

  override val initialAttributes: Attributes = Attributes.name("scanAsync")

  override val toString: String = "ScanAsync"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler { self ⇒

      private var current: Out = zero
      private var elementHandled: Boolean = false

      private def ec = ExecutionContexts.sameThreadExecutionContext

      private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      private val ZeroHandler: OutHandler with InHandler = new OutHandler with InHandler {
        override def onPush(): Unit =
          throw new IllegalStateException("No push should happen before zero value has been consumed")

        override def onPull(): Unit = {
          elementHandled = true
          push(out, current)
          setHandlers(in, out, self)
        }

        override def onUpstreamFinish(): Unit = setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            push(out, current)
            completeStage()
          }
        })
      }

      private def onRestart(t: Throwable): Unit = {
        current = zero
        elementHandled = false
      }

      private def safePull(): Unit = {
        if (isClosed(in)) {
          completeStage()
        } else if (isAvailable(out)) {
          if (!hasBeenPulled(in)) {
            tryPull(in)
          }
        }
      }

      private def pushAndPullOrFinish(update: Out): Unit = {
        push(out, update)
        safePull()
      }

      private def doSupervision(t: Throwable): Unit = {
        decider(t) match {
          case Supervision.Stop   ⇒ failStage(t)
          case Supervision.Resume ⇒ safePull()
          case Supervision.Restart ⇒
            onRestart(t)
            safePull()
        }
        elementHandled = true
      }

      private val futureCB = getAsyncCallback[Try[Out]] {
        case Success(next) if next != null ⇒
          current = next
          pushAndPullOrFinish(next)
          elementHandled = true
        case Success(null) ⇒ doSupervision(ReactiveStreamsCompliance.elementMustNotBeNullException)
        case Failure(t)    ⇒ doSupervision(t)
      }.invoke _

      setHandlers(in, out, ZeroHandler)

      def onPull(): Unit = safePull()

      def onPush(): Unit = {
        try {
          elementHandled = false

          val eventualCurrent = f(current, grab(in))

          eventualCurrent.value match {
            case Some(result) ⇒ futureCB(result)
            case _            ⇒ eventualCurrent.onComplete(futureCB)(ec)
          }
        } catch {
          case NonFatal(ex) ⇒
            decider(ex) match {
              case Supervision.Stop    ⇒ failStage(ex)
              case Supervision.Restart ⇒ onRestart(ex)
              case Supervision.Resume  ⇒ ()
            }
            tryPull(in)
            elementHandled = true
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (elementHandled) {
          completeStage()
        }
      }

      override val toString: String = s"ScanAsync.Logic(completed=$elementHandled)"
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Fold[In, Out](zero: Out, f: (Out, In) ⇒ Out) extends GraphStage[FlowShape[In, Out]] {

  val in = Inlet[In]("Fold.in")
  val out = Outlet[Out]("Fold.out")
  override val shape: FlowShape[In, Out] = FlowShape(in, out)

  override def toString: String = "Fold"

  override val initialAttributes = DefaultAttributes.fold

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var aggregator: Out = zero

      private def decider =
        inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      override def onPush(): Unit = {
        val elem = grab(in)
        try {
          aggregator = f(aggregator, elem)
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop    ⇒ failStage(ex)
            case Supervision.Restart ⇒ aggregator = zero
            case _                   ⇒ ()
          }
        } finally {
          if (!isClosed(in)) pull(in)
        }
      }

      override def onPull(): Unit = {
        if (isClosed(in)) {
          push(out, aggregator)
          completeStage()
        } else {
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (isAvailable(out)) {
          push(out, aggregator)
          completeStage()
        }
      }

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class FoldAsync[In, Out](zero: Out, f: (Out, In) ⇒ Future[Out]) extends GraphStage[FlowShape[In, Out]] {

  import akka.dispatch.ExecutionContexts

  val in = Inlet[In]("FoldAsync.in")
  val out = Outlet[Out]("FoldAsync.out")
  val shape = FlowShape.of(in, out)

  override def toString: String = "FoldAsync"

  override val initialAttributes = DefaultAttributes.foldAsync

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      private var aggregator: Out = zero
      private var aggregating: Future[Out] = Future.successful(aggregator)

      private def onRestart(t: Throwable): Unit = {
        aggregator = zero
      }

      private def ec = ExecutionContexts.sameThreadExecutionContext

      private val futureCB = getAsyncCallback[Try[Out]] {
        case Success(update) if update != null ⇒
          aggregator = update

          if (isClosed(in)) {
            push(out, update)
            completeStage()
          } else if (isAvailable(out) && !hasBeenPulled(in)) tryPull(in)

        case other ⇒
          val ex = other match {
            case Failure(t) ⇒ t
            case Success(s) if s == null ⇒
              ReactiveStreamsCompliance.elementMustNotBeNullException
          }
          val supervision = decider(ex)

          if (supervision == Supervision.Stop) failStage(ex)
          else {
            if (supervision == Supervision.Restart) onRestart(ex)

            if (isClosed(in)) {
              push(out, aggregator)
              completeStage()
            } else if (isAvailable(out) && !hasBeenPulled(in)) tryPull(in)
          }
      }.invoke _

      def onPush(): Unit = {
        try {
          aggregating = f(aggregator, grab(in))
          handleAggregatingValue()
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case supervision ⇒ {
              supervision match {
                case Supervision.Restart ⇒ onRestart(ex)
                case _                   ⇒ () // just ignore on Resume
              }

              tryPull(in)
            }
          }
        }
      }

      override def onUpstreamFinish(): Unit = {
        handleAggregatingValue()
      }

      def onPull(): Unit = if (!hasBeenPulled(in)) tryPull(in)

      private def handleAggregatingValue(): Unit = {
        aggregating.value match {
          case Some(result) ⇒ futureCB(result) // already completed
          case _            ⇒ aggregating.onComplete(futureCB)(ec)
        }
      }

      setHandlers(in, out, this)

      override def toString =
        s"FoldAsync.Logic(completed=${aggregating.isCompleted})"
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Intersperse[T](start: Option[T], inject: T, end: Option[T]) extends SimpleLinearGraphStage[T] {
  ReactiveStreamsCompliance.requireNonNullElement(inject)
  if (start.isDefined) ReactiveStreamsCompliance.requireNonNullElement(start.get)
  if (end.isDefined) ReactiveStreamsCompliance.requireNonNullElement(end.get)

  override def createLogic(attr: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {
    val startInHandler = new InHandler {
      override def onPush(): Unit = {
        // if else (to avoid using Iterator[T].flatten in hot code)
        if (start.isDefined) emitMultiple(out, Iterator(start.get, grab(in)))
        else emit(out, grab(in))
        setHandler(in, restInHandler) // switch handler
      }

      override def onUpstreamFinish(): Unit = {
        emitMultiple(out, Iterator(start, end).flatten)
        completeStage()
      }
    }

    val restInHandler = new InHandler {
      override def onPush(): Unit = emitMultiple(out, Iterator(inject, grab(in)))

      override def onUpstreamFinish(): Unit = {
        if (end.isDefined) emit(out, end.get)
        completeStage()
      }
    }

    def onPull(): Unit = pull(in)

    setHandler(in, startInHandler)
    setHandler(out, this)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Grouped[T](n: Int) extends GraphStage[FlowShape[T, immutable.Seq[T]]] {
  require(n > 0, "n must be greater than 0")

  val in = Inlet[T]("Grouped.in")
  val out = Outlet[immutable.Seq[T]]("Grouped.out")
  override val shape: FlowShape[T, immutable.Seq[T]] = FlowShape(in, out)

  override protected val initialAttributes: Attributes = DefaultAttributes.grouped

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private val buf = {
      val b = Vector.newBuilder[T]
      b.sizeHint(n)
      b
    }
    var left = n

    override def onPush(): Unit = {
      buf += grab(in)
      left -= 1
      if (left == 0) {
        val elements = buf.result()
        buf.clear()
        left = n
        push(out, elements)
      } else {
        pull(in)
      }
    }

    override def onPull(): Unit = {
      pull(in)
    }

    override def onUpstreamFinish(): Unit = {
      // This means the buf is filled with some elements but not enough (left < n) to group together.
      // Since the upstream has finished we have to push them to downstream though.
      if (left < n) {
        val elements = buf.result()
        buf.clear()
        left = n
        push(out, elements)
      }
      completeStage()
    }

    setHandlers(in, out, this)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class LimitWeighted[T](val n: Long, val costFn: T ⇒ Long) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.limitWeighted

  def createLogic(inheritedAttributes: Attributes) = new SupervisedGraphStageLogic(inheritedAttributes, shape) with InHandler with OutHandler {
    private var left = n

    override def onPush(): Unit = {
      val elem = grab(in)
      withSupervision(() ⇒ costFn(elem)) match {
        case Some(weight) ⇒
          left -= weight
          if (left >= 0) push(out, elem) else failStage(new StreamLimitReachedException(n))
        case None ⇒ //do nothing
      }
    }

    override def onResume(t: Throwable): Unit = if (!hasBeenPulled(in)) pull(in)

    override def onRestart(t: Throwable): Unit = {
      left = n
      if (!hasBeenPulled(in)) pull(in)
    }

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

  override def toString = "LimitWeighted"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Sliding[T](val n: Int, val step: Int) extends GraphStage[FlowShape[T, immutable.Seq[T]]] {
  require(n > 0, "n must be greater than 0")
  require(step > 0, "step must be greater than 0")

  val in = Inlet[T]("Sliding.in")
  val out = Outlet[immutable.Seq[T]]("Sliding.out")
  override val shape: FlowShape[T, immutable.Seq[T]] = FlowShape(in, out)

  override protected val initialAttributes: Attributes = DefaultAttributes.sliding

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var buf = Vector.empty[T]

    override def onPush(): Unit = {
      buf :+= grab(in)
      if (buf.size < n) {
        pull(in)
      } else if (buf.size == n) {
        push(out, buf)
      } else if (step <= n) {
        buf = buf.drop(step)
        if (buf.size == n) {
          push(out, buf)
        } else pull(in)
      } else if (step > n) {
        if (buf.size == step) {
          buf = buf.drop(step)
        }
        pull(in)
      }
    }

    override def onPull(): Unit = {
      pull(in)
    }

    override def onUpstreamFinish(): Unit = {

      // We can finish current stage directly if:
      //  1. the buf is empty or
      //  2. when the step size is greater than the sliding size (step > n) and current stage is in between
      //     two sliding (ie. buf.size >= n && buf.size < step).
      //
      // Otherwise it means there is still a not finished sliding so we have to push them before finish current stage.
      if (buf.size < n && buf.size > 0) {
        push(out, buf)
      }
      completeStage()
    }

    this.setHandlers(in, out, this)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy) extends SimpleLinearGraphStage[T] {

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
    override protected def logSource: Class[_] = classOf[Buffer[_]]

    private var buffer: BufferImpl[T] = _

    val enqueueAction: T ⇒ Unit =
      overflowStrategy match {
        case s: DropHead ⇒ elem ⇒
          if (buffer.isFull) {
            log.log(s.logLevel, "Dropping the head element because buffer is full and overflowStrategy is: [DropHead]")
            buffer.dropHead()
          }
          buffer.enqueue(elem)
          pull(in)
        case s: DropTail ⇒ elem ⇒
          if (buffer.isFull) {
            log.log(s.logLevel, "Dropping the tail element because buffer is full and overflowStrategy is: [DropTail]")
            buffer.dropTail()
          }
          buffer.enqueue(elem)
          pull(in)
        case s: DropBuffer ⇒ elem ⇒
          if (buffer.isFull) {
            log.log(s.logLevel, "Dropping all the buffered elements because buffer is full and overflowStrategy is: [DropBuffer]")
            buffer.clear()
          }
          buffer.enqueue(elem)
          pull(in)
        case s: DropNew ⇒ elem ⇒
          if (!buffer.isFull) buffer.enqueue(elem)
          else log.log(s.logLevel, "Dropping the new element because buffer is full and overflowStrategy is: [DropNew]")
          pull(in)
        case s: Backpressure ⇒ elem ⇒
          buffer.enqueue(elem)
          if (!buffer.isFull) pull(in)
          else log.log(s.logLevel, "Backpressuring because buffer is full and overflowStrategy is: [Backpressure]")
        case s: Fail ⇒ elem ⇒
          if (buffer.isFull) {
            log.log(s.logLevel, "Failing because buffer is full and overflowStrategy is: [Fail]")
            failStage(BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
          } else {
            buffer.enqueue(elem)
            pull(in)
          }
      }

    override def preStart(): Unit = {
      buffer = BufferImpl(size, materializer)
      pull(in)
    }

    override def onPush(): Unit = {
      val elem = grab(in)
      // If out is available, then it has been pulled but no dequeued element has been delivered.
      // It means the buffer at this moment is definitely empty,
      // so we just push the current element to out, then pull.
      if (isAvailable(out)) {
        push(out, elem)
        pull(in)
      } else {
        enqueueAction(elem)
      }
    }

    override def onPull(): Unit = {
      if (buffer.nonEmpty) push(out, buffer.dequeue())
      if (isClosed(in)) {
        if (buffer.isEmpty) completeStage()
      } else if (!hasBeenPulled(in)) {
        pull(in)
      }
    }

    override def onUpstreamFinish(): Unit = {
      if (buffer.isEmpty) completeStage()
    }

    setHandlers(in, out, this)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Batch[In, Out](val max: Long, val costFn: In ⇒ Long, val seed: In ⇒ Out, val aggregate: (Out, In) ⇒ Out)
  extends GraphStage[FlowShape[In, Out]] {

  val in = Inlet[In]("Batch.in")
  val out = Outlet[Out]("Batch.out")

  override val shape: FlowShape[In, Out] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

    lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

    private var agg: Out = null.asInstanceOf[Out]
    private var left: Long = max
    private var pending: In = null.asInstanceOf[In]

    private def flush(): Unit = {
      if (agg != null) {
        push(out, agg)
        left = max
      }
      if (pending != null) {
        try {
          agg = seed(pending)
          left -= costFn(pending)
          pending = null.asInstanceOf[In]
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop    ⇒ failStage(ex)
            case Supervision.Restart ⇒ restartState()
            case Supervision.Resume ⇒
              pending = null.asInstanceOf[In]
          }
        }
      } else {
        agg = null.asInstanceOf[Out]
      }
    }

    override def preStart() = pull(in)

    def onPush(): Unit = {
      val elem = grab(in)
      val cost = costFn(elem)

      if (agg == null) {
        try {
          agg = seed(elem)
          left -= cost
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case Supervision.Restart ⇒
              restartState()
            case Supervision.Resume ⇒
          }
        }
      } else if (left < cost) {
        pending = elem
      } else {
        try {
          agg = aggregate(agg, elem)
          left -= cost
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case Supervision.Restart ⇒
              restartState()
            case Supervision.Resume ⇒
          }
        }
      }

      if (isAvailable(out)) flush()
      if (pending == null) pull(in)
    }

    override def onUpstreamFinish(): Unit = {
      if (agg == null) completeStage()
    }

    def onPull(): Unit = {
      if (agg == null) {
        if (isClosed(in)) completeStage()
        else if (!hasBeenPulled(in)) pull(in)
      } else if (isClosed(in)) {
        push(out, agg)
        if (pending == null) completeStage()
        else {
          try {
            agg = seed(pending)
          } catch {
            case NonFatal(ex) ⇒ decider(ex) match {
              case Supervision.Stop   ⇒ failStage(ex)
              case Supervision.Resume ⇒
              case Supervision.Restart ⇒
                restartState()
                if (!hasBeenPulled(in)) pull(in)
            }
          }
          pending = null.asInstanceOf[In]
        }
      } else {
        flush()
        if (!hasBeenPulled(in)) pull(in)
      }

    }

    private def restartState(): Unit = {
      agg = null.asInstanceOf[Out]
      left = max
      pending = null.asInstanceOf[In]
    }

    setHandlers(in, out, this)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Expand[In, Out](val extrapolate: In ⇒ Iterator[Out]) extends GraphStage[FlowShape[In, Out]] {
  private val in = Inlet[In]("expand.in")
  private val out = Outlet[Out]("expand.out")

  override def initialAttributes = DefaultAttributes.expand

  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var iterator: Iterator[Out] = Iterator.empty
    private var expanded = false

    override def preStart(): Unit = pull(in)

    def onPush(): Unit = {
      iterator = extrapolate(grab(in))
      if (iterator.hasNext) {
        if (isAvailable(out)) {
          expanded = true
          pull(in)
          push(out, iterator.next())
        } else expanded = false
      } else pull(in)
    }

    override def onUpstreamFinish(): Unit = {
      if (iterator.hasNext && !expanded) () // need to wait
      else completeStage()
    }

    def onPull(): Unit = {
      if (iterator.hasNext) {
        if (!expanded) {
          expanded = true
          if (isClosed(in)) {
            push(out, iterator.next())
            completeStage()
          } else {
            // expand needs to pull first to be “fair” when upstream is not actually slow
            pull(in)
            push(out, iterator.next())
          }
        } else push(out, iterator.next())
      }
    }

    setHandler(in, this)
    setHandler(out, this)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object MapAsync {

  final class Holder[T](
    var elem: Try[T],
    val cb:   AsyncCallback[Holder[T]]
  ) extends (Try[T] ⇒ Unit) {

    // To support both fail-fast when the supervision directive is Stop
    // and not calling the decider multiple times (#23888) we need to cache the decider result and re-use that
    private var cachedSupervisionDirective: OptionVal[Supervision.Directive] = OptionVal.None

    def supervisionDirectiveFor(decider: Supervision.Decider, ex: Throwable): Supervision.Directive = {
      cachedSupervisionDirective match {
        case OptionVal.Some(d) ⇒ d
        case OptionVal.None ⇒
          val d = decider(ex)
          cachedSupervisionDirective = OptionVal.Some(d)
          d
      }
    }

    def setElem(t: Try[T]): Unit = {
      elem = t match {
        case Success(null) ⇒ Failure[T](ReactiveStreamsCompliance.elementMustNotBeNullException)
        case other         ⇒ other
      }
    }

    override def apply(t: Try[T]): Unit = {
      setElem(t)
      cb.invoke(this)
    }
  }

  val NotYetThere = Failure(new Exception with NoStackTrace)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class MapAsync[In, Out](parallelism: Int, f: In ⇒ Future[Out])
  extends GraphStage[FlowShape[In, Out]] {

  import MapAsync._

  private val in = Inlet[In]("MapAsync.in")
  private val out = Outlet[Out]("MapAsync.out")

  override def initialAttributes = DefaultAttributes.mapAsync

  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      var buffer: BufferImpl[Holder[Out]] = _

      private val futureCB = getAsyncCallback[Holder[Out]](holder ⇒
        holder.elem match {
          case Success(_) ⇒ pushNextIfPossible()
          case Failure(ex) ⇒
            holder.supervisionDirectiveFor(decider, ex) match {
              // fail fast as if supervision says so
              case Supervision.Stop ⇒ failStage(ex)
              case _                ⇒ pushNextIfPossible()
            }
        })

      override def preStart(): Unit = buffer = BufferImpl(parallelism, materializer)

      override def onPull(): Unit = pushNextIfPossible()

      override def onPush(): Unit = {
        try {
          val future = f(grab(in))
          val holder = new Holder[Out](NotYetThere, futureCB)
          buffer.enqueue(holder)

          future.value match {
            case None ⇒ future.onComplete(holder)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
            case Some(v) ⇒
              // #20217 the future is already here, optimization: avoid scheduling it on the dispatcher and
              // run the logic directly on this thread
              holder.setElem(v)
              v match {
                // this optimization also requires us to stop the stage to fail fast if the decider says so:
                case Failure(ex) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop ⇒ failStage(ex)
                case _ ⇒ pushNextIfPossible()
              }
          }

        } catch {
          // this logic must only be executed if f throws, not if the future is failed
          case NonFatal(ex) ⇒ if (decider(ex) == Supervision.Stop) failStage(ex)
        }

        pullIfNeeded()
      }

      override def onUpstreamFinish(): Unit = if (buffer.isEmpty) completeStage()

      @tailrec
      private def pushNextIfPossible(): Unit =
        if (buffer.isEmpty) {
          if (isClosed(in)) completeStage()
          else pullIfNeeded()
        } else if (buffer.peek().elem eq NotYetThere) pullIfNeeded() // ahead of line blocking to keep order
        else if (isAvailable(out)) {
          val holder = buffer.dequeue()
          holder.elem match {
            case Success(elem) ⇒
              push(out, elem)
              pullIfNeeded()

            case Failure(NonFatal(ex)) ⇒
              holder.supervisionDirectiveFor(decider, ex) match {
                // this could happen if we are looping in pushNextIfPossible and end up on a failed future before the
                // onComplete callback has run
                case Supervision.Stop ⇒ failStage(ex)
                case _ ⇒
                  // try next element
                  pushNextIfPossible()
              }
          }
        }

      private def pullIfNeeded(): Unit = {
        if (buffer.used < parallelism && !hasBeenPulled(in)) tryPull(in)
      }

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class MapAsyncUnordered[In, Out](parallelism: Int, f: In ⇒ Future[Out])
  extends GraphStage[FlowShape[In, Out]] {

  private val in = Inlet[In]("MapAsyncUnordered.in")
  private val out = Outlet[Out]("MapAsyncUnordered.out")

  override def initialAttributes = DefaultAttributes.mapAsyncUnordered

  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      override def toString = s"MapAsyncUnordered.Logic(inFlight=$inFlight, buffer=$buffer)"

      val decider =
        inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      private var inFlight = 0
      private var buffer: BufferImpl[Out] = _

      private[this] def todo = inFlight + buffer.used

      override def preStart(): Unit = buffer = BufferImpl(parallelism, materializer)

      def futureCompleted(result: Try[Out]): Unit = {
        inFlight -= 1
        result match {
          case Success(elem) if elem != null ⇒
            if (isAvailable(out)) {
              if (!hasBeenPulled(in)) tryPull(in)
              push(out, elem)
            } else buffer.enqueue(elem)
          case other ⇒
            val ex = other match {
              case Failure(t)              ⇒ t
              case Success(s) if s == null ⇒ ReactiveStreamsCompliance.elementMustNotBeNullException
            }
            if (decider(ex) == Supervision.Stop) failStage(ex)
            else if (isClosed(in) && todo == 0) completeStage()
            else if (!hasBeenPulled(in)) tryPull(in)
        }
      }

      private val futureCB = getAsyncCallback(futureCompleted)
      private val invokeFutureCB: Try[Out] ⇒ Unit = futureCB.invoke

      override def onPush(): Unit = {
        try {
          val future = f(grab(in))
          inFlight += 1
          future.value match {
            case None    ⇒ future.onComplete(invokeFutureCB)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
            case Some(v) ⇒ futureCompleted(v)
          }
        } catch {
          case NonFatal(ex) ⇒ if (decider(ex) == Supervision.Stop) failStage(ex)
        }
        if (todo < parallelism && !hasBeenPulled(in)) tryPull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (todo == 0) completeStage()
      }

      override def onPull(): Unit = {
        if (!buffer.isEmpty) push(out, buffer.dequeue())
        else if (isClosed(in) && todo == 0) completeStage()

        if (todo < parallelism && !hasBeenPulled(in)) tryPull(in)
      }

      setHandlers(in, out, this)
    }
}

@InternalApi private[akka] final case class Watch[T](targetRef: ActorRef) extends SimpleLinearGraphStage[T] {

  override def initialAttributes = DefaultAttributes.watch

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

      private lazy val self = getStageActor {
        case (_, Terminated(`targetRef`)) ⇒
          failStage(new WatchedActorTerminatedException("Watch", targetRef))
      }

      override def preStart(): Unit = {
        // initialize self, and watch the target
        self.watch(targetRef)
      }

      override def onPull(): Unit =
        pull(in)

      override def onPush(): Unit =
        push(out, grab(in))

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class Log[T](
  name:       String,
  extract:    T ⇒ Any,
  logAdapter: Option[LoggingAdapter]) extends SimpleLinearGraphStage[T] {

  override def toString = "Log"

  // TODO more optimisations can be done here - prepare logOnPush function etc
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler with InHandler {

      import Log._

      private var logLevels: LogLevels = _
      private var log: LoggingAdapter = _

      def decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      override def preStart(): Unit = {
        logLevels = inheritedAttributes.get[LogLevels](DefaultLogLevels)
        log = logAdapter match {
          case Some(l) ⇒ l
          case _ ⇒
            val mat = try ActorMaterializerHelper.downcast(materializer)
            catch {
              case ex: Exception ⇒
                throw new RuntimeException("Log stage can only provide LoggingAdapter when used with ActorMaterializer! " +
                  "Provide a LoggingAdapter explicitly or use the actor based flow materializer.", ex)
            }

            Logging(mat.system, mat)(fromMaterializer)
        }
      }

      override def onPush(): Unit = {
        try {
          val elem = grab(in)
          if (isEnabled(logLevels.onElement))
            log.log(logLevels.onElement, "[{}] Element: {}", name, extract(elem))

          push(out, elem)
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒ failStage(ex)
            case _                ⇒ pull(in)
          }
        }
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFailure(cause: Throwable): Unit = {
        if (isEnabled(logLevels.onFailure))
          logLevels.onFailure match {
            case Logging.ErrorLevel ⇒ log.error(cause, "[{}] Upstream failed.", name)
            case level ⇒ log.log(level, "[{}] Upstream failed, cause: {}: {}", name, Logging.simpleName(cause
              .getClass), cause.getMessage)
          }

        super.onUpstreamFailure(cause)
      }

      override def onUpstreamFinish(): Unit = {
        if (isEnabled(logLevels.onFinish))
          log.log(logLevels.onFinish, "[{}] Upstream finished.", name)

        super.onUpstreamFinish()
      }

      override def onDownstreamFinish(): Unit = {
        if (isEnabled(logLevels.onFinish))
          log.log(logLevels.onFinish, "[{}] Downstream finished.", name)

        super.onDownstreamFinish()
      }

      private def isEnabled(l: LogLevel): Boolean = l.asInt != OffInt

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object Log {

  /**
   * Must be located here to be visible for implicit resolution, when [[Materializer]] is passed to [[Logging]]
   * More specific LogSource than `fromString`, which would add the ActorSystem name in addition to the supervision to the log source.
   */
  final val fromMaterializer = new LogSource[Materializer] {

    // do not expose private context classes (of OneBoundedInterpreter)
    override def getClazz(t: Materializer): Class[_] = classOf[Materializer]

    override def genString(t: Materializer): String = {
      try s"$DefaultLoggerName(${ActorMaterializerHelper.downcast(t).supervisor.path})"
      catch {
        case ex: Exception ⇒ LogSource.fromString.genString(DefaultLoggerName)
      }
    }

  }

  private final val DefaultLoggerName = "akka.stream.Log"
  private final val OffInt = LogLevels.Off.asInt
  private final val DefaultLogLevels = LogLevels(onElement = Logging.DebugLevel, onFinish = Logging
    .DebugLevel, onFailure = Logging.ErrorLevel)
}

/**
 * INTERNAL API
 */
@InternalApi private[stream] object TimerKeys {

  case object TakeWithinTimerKey

  case object DropWithinTimerKey

  case object GroupedWithinTimerKey

}

@InternalApi private[akka] object GroupedWeightedWithin {
  val groupedWeightedWithinTimer = "GroupedWeightedWithinTimer"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class GroupedWeightedWithin[T](val maxWeight: Long, costFn: T ⇒ Long, val interval: FiniteDuration) extends GraphStage[FlowShape[T, immutable.Seq[T]]] {
  require(maxWeight > 0, "maxWeight must be greater than 0")
  require(interval > Duration.Zero)

  val in = Inlet[T]("in")
  val out = Outlet[immutable.Seq[T]]("out")

  override def initialAttributes = DefaultAttributes.groupedWeightedWithin

  val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {

    private val buf: VectorBuilder[T] = new VectorBuilder
    private var pending: T = null.asInstanceOf[T]
    private var pendingWeight: Long = 0L
    // True if:
    // - buf is nonEmpty
    //       AND
    // - (timer fired
    //        OR
    //    totalWeight >= maxWeight
    //        OR
    //    pending != null
    //        OR
    //    upstream completed)
    private var pushEagerly = false
    private var groupEmitted = true
    private var finished = false
    private var totalWeight = 0L
    private var hasElements = false

    override def preStart() = {
      schedulePeriodically(GroupedWeightedWithin.groupedWeightedWithinTimer, interval)
      pull(in)
    }

    private def nextElement(elem: T): Unit = {
      groupEmitted = false
      val cost = costFn(elem)
      if (cost < 0L) failStage(new IllegalArgumentException(s"Negative weight [$cost] for element [$elem] is not allowed"))
      else {
        hasElements = true
        if (totalWeight + cost <= maxWeight) {
          buf += elem
          totalWeight += cost

          if (totalWeight < maxWeight) pull(in)
          else {
            // `totalWeight >= maxWeight` which means that downstream can get the next group.
            if (!isAvailable(out)) {
              // We should emit group when downstream becomes available
              pushEagerly = true
              // we want to pull anyway, since we allow for zero weight elements
              // but since `emitGroup()` will pull internally (by calling `startNewGroup()`)
              // we also have to pull if downstream hasn't yet requested an element.
              pull(in)
            } else {
              schedulePeriodically(GroupedWeightedWithin.groupedWeightedWithinTimer, interval)
              emitGroup()
            }
          }
        } else {
          //we have a single heavy element that weighs more than the limit
          if (totalWeight == 0L) {
            buf += elem
            totalWeight += cost
            pushEagerly = true
          } else {
            pending = elem
            pendingWeight = cost
          }
          schedulePeriodically(GroupedWeightedWithin.groupedWeightedWithinTimer, interval)
          tryCloseGroup()
        }
      }
    }

    private def tryCloseGroup(): Unit = {
      if (isAvailable(out)) emitGroup()
      else if (pending != null || finished) pushEagerly = true
    }

    private def emitGroup(): Unit = {
      groupEmitted = true
      push(out, buf.result())
      buf.clear()
      if (!finished) startNewGroup()
      else if (pending != null) emit(out, Vector(pending), () ⇒ completeStage())
      else completeStage()
    }

    private def startNewGroup(): Unit = {
      if (pending != null) {
        totalWeight = pendingWeight
        pendingWeight = 0L
        buf += pending
        pending = null.asInstanceOf[T]
        groupEmitted = false
      } else {
        totalWeight = 0L
        hasElements = false
      }
      pushEagerly = false
      if (isAvailable(in)) nextElement(grab(in))
      else if (!hasBeenPulled(in)) pull(in)
    }

    override def onPush(): Unit = {
      if (pending == null) nextElement(grab(in)) // otherwise keep the element for next round
    }

    override def onPull(): Unit = if (pushEagerly) emitGroup()

    override def onUpstreamFinish(): Unit = {
      finished = true
      if (groupEmitted) completeStage()
      else tryCloseGroup()
    }

    override protected def onTimer(timerKey: Any) = if (hasElements) {
      if (isAvailable(out)) emitGroup()
      else pushEagerly = true
    }

    setHandlers(in, out, this)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Delay[T](val d: FiniteDuration, val strategy: DelayOverflowStrategy) extends SimpleLinearGraphStage[T] {
  private[this] def timerName = "DelayedTimer"

  final val DelayPrecisionMS = 10

  override def initialAttributes: Attributes = DefaultAttributes.delay

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
    val size = inheritedAttributes.mandatoryAttribute[InputBuffer].max

    val delayMillis = d.toMillis

    var buffer: BufferImpl[(Long, T)] = _ // buffer has pairs timestamp with upstream element

    override def preStart(): Unit = buffer = BufferImpl(size, materializer)

    val onPushWhenBufferFull: () ⇒ Unit = strategy match {
      case EmitEarly ⇒
        () ⇒ {
          if (!isTimerActive(timerName))
            push(out, buffer.dequeue()._2)
          else {
            cancelTimer(timerName)
            onTimer(timerName)
          }
          grabAndPull()
        }
      case _: DropHead ⇒
        () ⇒ {
          buffer.dropHead()
          grabAndPull()
        }
      case _: DropTail ⇒
        () ⇒ {
          buffer.dropTail()
          grabAndPull()
        }
      case _: DropNew ⇒
        () ⇒ {
          grab(in)
          if (!isTimerActive(timerName)) scheduleOnce(timerName, d)
        }
      case _: DropBuffer ⇒
        () ⇒ {
          buffer.clear()
          grabAndPull()
        }
      case _: Fail ⇒
        () ⇒ {
          failStage(BufferOverflowException(s"Buffer overflow for delay operator (max capacity was: $size)!"))
        }
      case _: Backpressure ⇒
        () ⇒ {
          throw new IllegalStateException("Delay buffer must never overflow in Backpressure mode")
        }
    }

    def onPush(): Unit = {
      if (buffer.isFull)
        onPushWhenBufferFull()
      else {
        grabAndPull()
        if (!isTimerActive(timerName)) {
          scheduleOnce(timerName, d)
        }
      }
    }

    def pullCondition: Boolean =
      !strategy.isBackpressure || buffer.used < size

    def grabAndPull(): Unit = {
      buffer.enqueue((System.nanoTime(), grab(in)))
      if (pullCondition) pull(in)
    }

    override def onUpstreamFinish(): Unit =
      completeIfReady()

    def onPull(): Unit = {
      if (!isTimerActive(timerName) && !buffer.isEmpty) {
        val waitTime = nextElementWaitTime()
        if (waitTime < 0) {
          push(out, buffer.dequeue()._2)
        } else {
          scheduleOnce(timerName, Math.max(DelayPrecisionMS, waitTime).millis)
        }
      }

      if (!isClosed(in) && !hasBeenPulled(in) && pullCondition)
        pull(in)

      completeIfReady()
    }

    setHandler(in, this)
    setHandler(out, this)

    def completeIfReady(): Unit = if (isClosed(in) && buffer.isEmpty) completeStage()

    def nextElementWaitTime(): Long = {
      delayMillis - NANOSECONDS.toMillis(System.nanoTime() - buffer.peek()._1)
    }

    final override protected def onTimer(key: Any): Unit = {
      if (isAvailable(out))
        push(out, buffer.dequeue()._2)

      if (!buffer.isEmpty) {
        val waitTime = nextElementWaitTime()
        if (waitTime > DelayPrecisionMS)
          scheduleOnce(timerName, waitTime.millis)
      }
      completeIfReady()
    }
  }

  override def toString = "Delay"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class TakeWithin[T](val timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
    def onPush(): Unit = push(out, grab(in))

    def onPull(): Unit = pull(in)

    setHandlers(in, out, this)

    final override protected def onTimer(key: Any): Unit = completeStage()

    override def preStart(): Unit = scheduleOnce("TakeWithinTimer", timeout)
  }

  override def toString = "TakeWithin"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class DropWithin[T](val timeout: FiniteDuration) extends SimpleLinearGraphStage[T] {
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

    private val startNanoTime = System.nanoTime()
    private val timeoutInNano = timeout.toNanos

    def onPush(): Unit = {
      if (System.nanoTime() - startNanoTime <= timeoutInNano) {
        pull(in)
      } else {
        push(out, grab(in))
        // change the in handler to avoid System.nanoTime call after timeout
        setHandler(in, new InHandler {
          def onPush() = push(out, grab(in))
        })
      }
    }

    def onPull(): Unit = pull(in)

    setHandlers(in, out, this)

  }

  override def toString = "DropWithin"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Reduce[T](val f: (T, T) ⇒ T) extends SimpleLinearGraphStage[T] {
  override def initialAttributes: Attributes = DefaultAttributes.reduce

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler { self ⇒
    override def toString = s"Reduce.Logic(aggregator=$aggregator)"

    var aggregator: T = _

    private def decider =
      inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

    def setInitialInHandler(): Unit = {
      // Initial input handler
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          aggregator = grab(in)
          pull(in)
          setHandler(in, self)
        }

        override def onUpstreamFinish(): Unit =
          failStage(new NoSuchElementException("reduce over empty stream"))
      })
    }

    override def onPush(): Unit = {
      val elem = grab(in)
      try {
        aggregator = f(aggregator, elem)
      } catch {
        case NonFatal(ex) ⇒ decider(ex) match {
          case Supervision.Stop ⇒ failStage(ex)
          case Supervision.Restart ⇒
            aggregator = _: T
            setInitialInHandler()
          case _ ⇒ ()

        }
      } finally {
        if (!isClosed(in)) pull(in)
      }
    }

    override def onPull(): Unit = pull(in)

    override def onUpstreamFinish(): Unit = {
      push(out, aggregator)
      completeStage()
    }

    setInitialInHandler()
    setHandler(out, self)
  }

  override def toString = "Reduce"
}

/**
 * INTERNAL API
 */
@InternalApi private[stream] object RecoverWith

@InternalApi private[akka] final class RecoverWith[T, M](val maximumRetries: Int, val pf: PartialFunction[Throwable, Graph[SourceShape[T], M]]) extends SimpleLinearGraphStage[T] {

  override def initialAttributes = DefaultAttributes.recoverWith

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
    var attempt = 0

    setHandler(in, new InHandler {
      override def onPush(): Unit = push(out, grab(in))

      override def onUpstreamFailure(ex: Throwable) = onFailure(ex)
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    def onFailure(ex: Throwable) =
      if ((maximumRetries < 0 || attempt < maximumRetries) && pf.isDefinedAt(ex)) {
        switchTo(pf(ex))
        attempt += 1
      } else
        failStage(ex)

    def switchTo(source: Graph[SourceShape[T], M]): Unit = {
      val sinkIn = new SubSinkInlet[T]("RecoverWithSink")

      sinkIn.setHandler(new InHandler {
        override def onPush(): Unit = push(out, sinkIn.grab())

        override def onUpstreamFinish(): Unit = completeStage()

        override def onUpstreamFailure(ex: Throwable) = onFailure(ex)
      })

      val outHandler = new OutHandler {
        override def onPull(): Unit = sinkIn.pull()

        override def onDownstreamFinish(): Unit = sinkIn.cancel()
      }

      Source.fromGraph(source).runWith(sinkIn.sink)(interpreter.subFusingMaterializer)
      setHandler(out, outHandler)
      if (isAvailable(out)) sinkIn.pull()
    }
  }

  override def toString: String = "RecoverWith"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class StatefulMapConcat[In, Out](val f: () ⇒ In ⇒ immutable.Iterable[Out]) extends GraphStage[FlowShape[In, Out]] {
  val in = Inlet[In]("StatefulMapConcat.in")
  val out = Outlet[Out]("StatefulMapConcat.out")
  override val shape = FlowShape(in, out)

  override def initialAttributes: Attributes = DefaultAttributes.statefulMapConcat

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
    var currentIterator: Iterator[Out] = _
    var plainFun = f()

    def hasNext = if (currentIterator != null) currentIterator.hasNext else false

    setHandlers(in, out, this)

    def pushPull(): Unit =
      if (hasNext) {
        push(out, currentIterator.next())
        if (!hasNext && isClosed(in)) completeStage()
      } else if (!isClosed(in))
        pull(in)
      else completeStage()

    def onFinish(): Unit = if (!hasNext) completeStage()

    override def onPush(): Unit =
      try {
        currentIterator = plainFun(grab(in)).iterator
        pushPull()
      } catch handleException

    override def onUpstreamFinish(): Unit = onFinish()

    override def onPull(): Unit =
      try pushPull()
      catch handleException

    private def handleException: Catcher[Unit] = {
      case NonFatal(ex) ⇒ decider(ex) match {
        case Supervision.Stop ⇒ failStage(ex)
        case Supervision.Resume ⇒
          if (isClosed(in)) completeStage()
          else if (!hasBeenPulled(in)) pull(in)
        case Supervision.Restart ⇒
          if (isClosed(in)) completeStage()
          else {
            restartState()
            if (!hasBeenPulled(in)) pull(in)
          }
      }
    }

    private def restartState(): Unit = {
      plainFun = f()
      currentIterator = null
    }
  }

  override def toString = "StatefulMapConcat"

}

/**
 * INTERNAL API
 */
@InternalApi final private[akka] class LazyFlow[I, O, M](flowFactory: I ⇒ Future[Flow[I, O, M]])
  extends GraphStageWithMaterializedValue[FlowShape[I, O], Future[Option[M]]] {
  val in = Inlet[I]("lazyFlow.in")
  val out = Outlet[O]("lazyFlow.out")

  override def initialAttributes = DefaultAttributes.lazyFlow

  override val shape: FlowShape[I, O] = FlowShape.of(in, out)

  override def toString: String = "LazyFlow"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {

    val matPromise = Promise[Option[M]]()
    val stageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

      var switching = false

      //
      // implementation of handler methods in initial state
      //

      override def onPush(): Unit = {
        val element = grab(in)
        switching = true
        val cb = getAsyncCallback[Try[Flow[I, O, M]]] {
          case Success(flow) ⇒
            // check if the stage is still in need for the lazy flow
            // (there could have been an onUpstreamFailure or onDownstreamFinish in the meantime that has completed the promise)
            if (!matPromise.isCompleted) {
              try {
                val mat = switchTo(flow, element)
                matPromise.success(Some(mat))
              } catch {
                case NonFatal(e) ⇒
                  matPromise.failure(e)
                  failStage(e)
              }
            }
          case Failure(e) ⇒
            matPromise.failure(e)
            failStage(e)
        }
        try {
          flowFactory(element).onComplete(cb.invoke)(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒
            matPromise.failure(e)
            failStage(e)
        }
      }

      override def onUpstreamFinish(): Unit = {
        // ignore onUpstreamFinish while the stage is switching but setKeepGoing
        if (switching) {
          setKeepGoing(true)
        } else {
          matPromise.success(None)
          super.onUpstreamFinish()
        }
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        matPromise.failure(ex)
        super.onUpstreamFailure(ex)
      }

      override def onDownstreamFinish(): Unit = {
        matPromise.success(None)
        super.onDownstreamFinish()
      }

      override def onPull(): Unit = {
        pull(in)
      }

      setHandler(in, this)
      setHandler(out, this)

      private def switchTo(flow: Flow[I, O, M], firstElement: I): M = {

        var firstElementPushed = false

        //
        // ports are wired in the following way:
        //
        // in ~> subOutlet ~> lazyFlow ~> subInlet ~> out
        //

        val subInlet = new SubSinkInlet[O]("LazyFlowSubSink")
        val subOutlet = new SubSourceOutlet[I]("LazyFlowSubSource")

        val matVal = Source.fromGraph(subOutlet.source)
          .viaMat(flow)(Keep.right)
          .toMat(subInlet.sink)(Keep.left)
          .run()(interpreter.subFusingMaterializer)

        // The lazily materialized flow may be constructed from a sink and a source. Therefore termination
        // signals (completion, cancellation, and errors) are not guaranteed to pass through the flow. This
        // means that this stage must not be completed as soon as one side of the flow is finished.
        //
        // Invariant: isClosed(out) == subInlet.isClosed after each event because termination signals (i.e.
        // completion, cancellation, and failure) between these two ports are always forwarded.
        //
        // However, isClosed(in) and subOutlet.isClosed may be different. This happens if upstream completes before
        // the cached element was pushed.
        def maybeCompleteStage(): Unit = {
          if (isClosed(in) && subOutlet.isClosed && isClosed(out)) {
            completeStage()
          }
        }

        // The stage must not be shut down automatically; it is completed when maybeCompleteStage decides
        setKeepGoing(true)

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            subOutlet.push(grab(in))
          }
          override def onUpstreamFinish(): Unit = {
            if (firstElementPushed) {
              subOutlet.complete()
              maybeCompleteStage()
            }
          }
          override def onUpstreamFailure(ex: Throwable): Unit = {
            // propagate exception irrespective if the cached element has been pushed or not
            subOutlet.fail(ex)
            maybeCompleteStage()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            subInlet.pull()
          }
          override def onDownstreamFinish(): Unit = {
            subInlet.cancel()
            maybeCompleteStage()
          }
        })

        subOutlet.setHandler(new OutHandler {
          override def onPull(): Unit = {
            if (firstElementPushed) {
              pull(in)
            } else {
              // the demand can be satisfied right away by the cached element
              firstElementPushed = true
              subOutlet.push(firstElement)
              // in.onUpstreamFinished was not propagated if it arrived before the cached element was pushed
              // -> check if the completion must be propagated now
              if (isClosed(in)) {
                subOutlet.complete()
                maybeCompleteStage()
              }
            }
          }
          override def onDownstreamFinish(): Unit = {
            if (!isClosed(in)) {
              cancel(in)
            }
            maybeCompleteStage()
          }
        })

        subInlet.setHandler(new InHandler {
          override def onPush(): Unit = {
            push(out, subInlet.grab())
          }
          override def onUpstreamFinish(): Unit = {
            complete(out)
            maybeCompleteStage()
          }
          override def onUpstreamFailure(ex: Throwable): Unit = {
            fail(out, ex)
            maybeCompleteStage()
          }
        })

        if (isClosed(out)) {
          // downstream may have been canceled while the stage was switching
          subInlet.cancel()
        } else {
          subInlet.pull()
        }

        matVal
      }

    }
    (stageLogic, matPromise.future)
  }
}
