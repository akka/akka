/*
 * Copyright (C) 2020-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import akka.dispatch.AbstractBoundedNodeQueue
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, OutHandler }
import akka.stream.{ Attributes, Outlet, SourceShape }

import scala.annotation.tailrec

trait FastDroppingQueue[T] {

  /**
   * Returns true if element could be enqueued and false if not.
   *
   * Even if it returns true it does not guarantee that an element also has been or will be processed by the downstream.
   */
  def offer(elem: T): FastDroppingQueue.OfferResult

  def complete(): Unit
  def fail(ex: Throwable): Unit
}

object FastDroppingQueue {

  /**
   * A queue of the given size that gives immediate feedback whether an element could be enqueued or not.
   * @param size
   * @tparam T
   * @return
   */
  def apply[T](size: Int): Source[T, FastDroppingQueue[T]] =
    Source.fromGraph(new FastDroppingQueueStage[T](size))

  sealed trait OfferResult
  object OfferResult {
    case object Enqueued extends OfferResult
    case object Dropped extends OfferResult

    sealed trait CompletionResult extends OfferResult
    case object Completed extends CompletionResult
    case object Cancelled extends CompletionResult
    case class Failed(cause: Throwable) extends CompletionResult
  }
}
class FastDroppingQueueStage[T](bufferSize: Int)
    extends GraphStageWithMaterializedValue[SourceShape[T], FastDroppingQueue[T]] {
  val out = Outlet[T]("FastDroppingQueueStage.out")
  val shape = SourceShape(out)

  override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes): (GraphStageLogic, FastDroppingQueue[T]) = {
    import FastDroppingQueue._

    sealed trait State
    case object NeedsActivation extends State
    case object Running extends State
    case class Done(result: OfferResult.CompletionResult) extends State

    val state = new AtomicReference[State](Running)

    val queue = new AbstractBoundedNodeQueue[T](bufferSize) {}

    object Logic extends GraphStageLogic(shape) with OutHandler {
      setHandler(out, this)
      val callback = getAsyncCallback[Unit] { _ =>
        clearNeedsActivation()
        run()
      }

      override def onPull(): Unit = run()

      override def onDownstreamFinish(): Unit = {
        setDone(Done(OfferResult.Cancelled))

        super.onDownstreamFinish()
      }

      override def postStop(): Unit =
        // drain queue
        while (!queue.isEmpty) queue.poll()

      /**
       * Main loop of the queue. We do two volatile reads for the fast path of pushing elements
       * from the queue to the stream: one for the state and one to poll the queue. This leads to a somewhat simple design
       * that will quickly pick up failures from the queue interface.
       *
       * An even more optimized version could use a fast path in onPull to avoid reading the state for every element.
       */
      @tailrec
      def run(): Unit =
        state.get() match {
          case Running =>
            if (isAvailable(out)) {
              val next = queue.poll()
              if (next == null) { // queue empty
                if (!setNeedsActivation())
                  run() // didn't manage to set because stream has been completed in the meantime
                else if (!queue.isEmpty) /* && setNeedsActivation was true */ {
                  // tricky case: new element might have been added in the meantime without callback being sent because
                  // NeedsActivation had not yet been set

                  clearNeedsActivation()
                  run()
                } // else Queue.isEmpty && setNeedsActivation was true: waiting for next offer
              } else
                push(out, next) // and then: wait for pull
            } // else: wait for pull

          case Done(OfferResult.Completed) =>
            if (queue.isEmpty) completeStage()
            else if (isAvailable(out)) {
              push(out, queue.poll())
              run() // another round, might be empty now
            }
          // else !Queue.isEmpty: wait for pull to drain remaining elements
          case Done(OfferResult.Failed(ex)) => failStage(ex)
          case Done(OfferResult.Cancelled)  => throw new IllegalStateException // should not happen
          case NeedsActivation              => throw new IllegalStateException // needs to be cleared before
        }
    }

    object Mat extends FastDroppingQueue[T] {
      override def offer(elem: T): OfferResult = state.get() match {
        case Running | NeedsActivation =>
          if (queue.add(elem)) {
            // need to query state again because stage might have switched from Running -> NeedsActivation only after
            // the last state.get but before queue.add.
            if (state.get() == NeedsActivation)
              // if this thread wins the race to toggle the flag, schedule async callback here
              if (clearNeedsActivation())
                Logic.callback.invoke(())

            OfferResult.Enqueued
          } else
            OfferResult.Dropped
        case Done(result) => result
      }

      override def complete(): Unit = // FIXME: should we fail here in some way if it was already completed?
        if (setDone(Done(OfferResult.Completed)))
          Logic.callback.invoke(()) // if this thread won the completion race also schedule an async callback
      override def fail(ex: Throwable): Unit = // FIXME: should we fail here in some way if it was already completed?
        if (setDone(Done(OfferResult.Failed(ex))))
          Logic.callback.invoke(()) // if this thread won the completion race also schedule an async callback
    }

    // some state transition helpers
    @tailrec
    def setDone(done: Done): Boolean =
      state.get() match {
        case _: Done => false
        case x =>
          if (!state.compareAndSet(x, done)) setDone(done)
          else true
      }

    @tailrec
    def clearNeedsActivation(): Boolean =
      state.get() match {
        case NeedsActivation =>
          if (!state.compareAndSet(NeedsActivation, Running)) clearNeedsActivation()
          else true

        case _ => false
      }
    @tailrec
    def setNeedsActivation(): Boolean =
      state.get() match {
        case Running =>
          if (!state.compareAndSet(Running, NeedsActivation)) setNeedsActivation()
          else true

        case _ => false
      }

    (Logic, Mat)
  }
}
