/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.{ Attributes, Inlet, Materializer, SinkShape }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler }

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }

@InternalApi
sealed private[akka] trait Collect[T] {
  def collectAndCompleteNow(): Future[(Seq[T], Boolean)]
}

@InternalApi private[akka] object CollectorStage {
  def resultAfterSourceElements[T, U](source: Source[T, Any], flow: Flow[T, U, Any])(implicit materializer: Materializer): Future[(Seq[U], Boolean)] = {
    val collector =
      (source ++ Source.maybe[T] /* Never complete*/ )
        .via(flow)
        .runWith(new CollectorStage[U])
    collector.collectAndCompleteNow()
  }
}

/**
 * Stage similar to Sink.seq that provides a side-channel to get at the collection at any point.
 * It will return all the collected elements and a flag whether the stream was completed or not.
 *
 * When this is added to a fused flow it can be used to check what output and completion a given input
 * will bring. Because the side-channel will go through the mailbox of the ActorGraphInterpreter the
 * hope is that the fused stream will "run to exhaustion", i.e. it will run as far as it can, so that
 * when the side-channel event is processed, no other (internal) events are still to be processed. This
 * assumption does not hold if the source stream has asynchronous boundaries or components.
 *
 * TODO: allow multiple rescheduling of AsyncCallback to make it more likely that all internal queues
 * have been processed.
 */
@InternalApi
private[akka] class CollectorStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Collect[T]] {
  val in = Inlet[T]("CollectorStage.in")
  def shape: SinkShape[T] = SinkShape(in)

  /** State of the callback infrastructure */
  sealed trait State
  case object Uninitialized extends State
  case class Scheduled(callback: Promise[(Seq[T], Boolean)]) extends State
  case object Initialized extends State
  case class Completed(elements: Try[(Seq[T], Boolean)]) extends State

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Collect[T]) = {
    val logic = new GraphStageLogic(shape) with Collect[T] with InHandler {
      var collectedElements = new VectorBuilder[T]()
      val state = new AtomicReference[State](Uninitialized)

      val collectNowCallback = getAsyncCallback[Promise[(Seq[T], Boolean)]] { promise ⇒
        require(state.get().isInstanceOf[Scheduled])
        promise.complete(Success(collectedElements.result() → false))
        completeStage()
      }

      setHandler(in, this)
      override def preStart(): Unit = {
        pull(in)

        @tailrec def handleState(): Unit =
          state.get() match {
            case Uninitialized ⇒
              if (!state.compareAndSet(Uninitialized, Initialized)) handleState()
            case early @ Scheduled(callback) ⇒
              // make sure to go through asynchandler -> actor interpreter mailbox
              Future(collectNowCallback.invoke(callback))(materializer.executionContext)
            case s ⇒ throw new IllegalStateException(s"Unexpected state $s")
          }
        handleState()
      }
      def onPush(): Unit = {
        val data = grab(in)
        pull(in)
        collectedElements += data
      }

      def collectAndCompleteNow(): Future[(Seq[T], Boolean)] = {
        val p = Promise[(Seq[T], Boolean)]()

        @tailrec def handleState(): Unit =
          state.get() match {
            case Initialized ⇒
              if (!state.compareAndSet(Initialized, Scheduled(p))) handleState()
              else collectNowCallback.invoke(p)
            case Uninitialized ⇒
              if (!state.compareAndSet(Uninitialized, Scheduled(p))) handleState()
            case Completed(result) ⇒ p.complete(result)
            case s                 ⇒ throw new IllegalStateException(s"Unexpected state $s")
          }

        handleState()
        p.future
      }

      override def onUpstreamFinish(): Unit = onCompletion(Success(collectedElements.result() → true))
      override def onUpstreamFailure(ex: Throwable): Unit = onCompletion(Failure(ex))

      def onCompletion(els: Try[(Seq[T], Boolean)]): Unit =
        state.get() match {
          case Scheduled(p) ⇒
            p.complete(els)
            completeStage()

          case Initialized ⇒
            // unfortunately we need an extra state here because the stage will not receive any more async callbacks
            // when the single inlet has completed
            if (!state.compareAndSet(Initialized, Completed(els))) onCompletion(els)
          case s ⇒ throw new IllegalStateException(s"Unexpected state $s")
        }
    }
    (logic, logic)
  }
}