/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.CompletionStage

import akka.Done
import akka.annotation.InternalApi
import akka.stream.OverflowStrategies._
import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl.SourceQueueWithComplete
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object QueueSource {

  sealed trait Input[+T]
  final case class Offer[+T](elem: T, promise: Promise[QueueOfferResult]) extends Input[T]
  case object Completion extends Input[Nothing]
  final case class Failure(ex: Throwable) extends Input[Nothing]

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class QueueSource[T](maxBuffer: Int, overflowStrategy: OverflowStrategy) extends GraphStageWithMaterializedValue[SourceShape[T], SourceQueueWithComplete[T]] {
  import QueueSource._

  val out = Outlet[T]("queueSource.out")
  override val shape: SourceShape[T] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val completion = Promise[Done]

    val stageLogic = new GraphStageLogic(shape) with OutHandler with SourceQueueWithComplete[T] with StageLogging {
      override protected def logSource: Class[_] = classOf[QueueSource[_]]

      var buffer: Buffer[T] = _
      var pendingOffer: Option[Offer[T]] = None
      var terminating = false

      override def preStart(): Unit = {
        if (maxBuffer > 0) buffer = Buffer(maxBuffer, materializer)
      }
      override def postStop(): Unit = {
        val exception = new StreamDetachedException()
        completion.tryFailure(exception)
      }

      private def enqueueAndSuccess(offer: Offer[T]): Unit = {
        buffer.enqueue(offer.elem)
        offer.promise.success(QueueOfferResult.Enqueued)
      }

      private def bufferElem(offer: Offer[T]): Unit = {
        if (!buffer.isFull) {
          enqueueAndSuccess(offer)
        } else overflowStrategy match {
          case s: DropHead ⇒
            log.log(s.logLevel, "Dropping the head element because buffer is full and overflowStrategy is: [DropHead]")
            buffer.dropHead()
            enqueueAndSuccess(offer)
          case s: DropTail ⇒
            log.log(s.logLevel, "Dropping the tail element because buffer is full and overflowStrategy is: [DropTail]")
            buffer.dropTail()
            enqueueAndSuccess(offer)
          case s: DropBuffer ⇒
            log.log(s.logLevel, "Dropping all the buffered elements because buffer is full and overflowStrategy is: [DropBuffer]")
            buffer.clear()
            enqueueAndSuccess(offer)
          case s: DropNew ⇒
            log.log(s.logLevel, "Dropping the new element because buffer is full and overflowStrategy is: [DropNew]")
            offer.promise.success(QueueOfferResult.Dropped)
          case s: Fail ⇒
            log.log(s.logLevel, "Failing because buffer is full and overflowStrategy is: [Fail]")
            val bufferOverflowException = BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")
            offer.promise.success(QueueOfferResult.Failure(bufferOverflowException))
            completion.failure(bufferOverflowException)
            failStage(bufferOverflowException)
          case s: Backpressure ⇒
            log.log(s.logLevel, "Backpressuring because buffer is full and overflowStrategy is: [Backpressure]")
            pendingOffer match {
              case Some(_) ⇒
                offer.promise.failure(new IllegalStateException("You have to wait for previous offer to be resolved to send another request"))
              case None ⇒
                pendingOffer = Some(offer)
            }
        }
      }

      private val callback = getAsyncCallback[Input[T]] {
        case Offer(_, promise) if terminating ⇒
          promise.success(QueueOfferResult.Dropped)

        case offer @ Offer(elem, promise) ⇒
          if (maxBuffer != 0) {
            bufferElem(offer)
            if (isAvailable(out)) push(out, buffer.dequeue())
          } else if (isAvailable(out)) {
            push(out, elem)
            promise.success(QueueOfferResult.Enqueued)
          } else if (pendingOffer.isEmpty)
            pendingOffer = Some(offer)
          else overflowStrategy match {
            case s @ (_: DropHead | _: DropBuffer) ⇒
              log.log(s.logLevel, "Dropping element because buffer is full and overflowStrategy is: [{}]", s)
              pendingOffer.get.promise.success(QueueOfferResult.Dropped)
              pendingOffer = Some(offer)
            case s @ (_: DropTail | _: DropNew) ⇒
              log.log(s.logLevel, "Dropping element because buffer is full and overflowStrategy is: [{}]", s)
              promise.success(QueueOfferResult.Dropped)
            case s: Fail ⇒
              log.log(s.logLevel, "Failing because buffer is full and overflowStrategy is: [Fail]")
              val bufferOverflowException = BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")
              promise.success(QueueOfferResult.Failure(bufferOverflowException))
              completion.failure(bufferOverflowException)
              failStage(bufferOverflowException)
            case s: Backpressure ⇒
              log.log(s.logLevel, "Failing because buffer is full and overflowStrategy is: [Backpressure]")
              promise.failure(new IllegalStateException("You have to wait for previous offer to be resolved to send another request"))
          }

        case Completion ⇒
          if (maxBuffer != 0 && buffer.nonEmpty || pendingOffer.nonEmpty) terminating = true
          else {
            completion.success(Done)
            completeStage()
          }

        case Failure(ex) ⇒
          completion.failure(ex)
          failStage(ex)
      }

      setHandler(out, this)

      override def onDownstreamFinish(): Unit = {
        pendingOffer match {
          case Some(Offer(_, promise)) ⇒
            promise.success(QueueOfferResult.QueueClosed)
            pendingOffer = None
          case None ⇒ // do nothing
        }
        completion.success(Done)
        completeStage()
      }

      override def onPull(): Unit = {
        if (maxBuffer == 0) {
          pendingOffer match {
            case Some(Offer(elem, promise)) ⇒
              push(out, elem)
              promise.success(QueueOfferResult.Enqueued)
              pendingOffer = None
              if (terminating) {
                completion.success(Done)
                completeStage()
              }
            case None ⇒
          }
        } else if (buffer.nonEmpty) {
          push(out, buffer.dequeue())
          pendingOffer match {
            case Some(offer) ⇒
              enqueueAndSuccess(offer)
              pendingOffer = None
            case None ⇒ //do nothing
          }
          if (terminating && buffer.isEmpty) {
            completion.success(Done)
            completeStage()
          }
        }
      }

      override def watchCompletion() = completion.future
      override def offer(element: T): Future[QueueOfferResult] = {
        val p = Promise[QueueOfferResult]
        callback.invokeWithFeedback(Offer(element, p))
          .onComplete {
            case scala.util.Success(_) ⇒
            case scala.util.Failure(e) ⇒ p.tryFailure(e)
          }(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        p.future
      }
      override def complete(): Unit = callback.invoke(Completion)

      override def fail(ex: Throwable): Unit = callback.invoke(Failure(ex))

    }

    (stageLogic, stageLogic)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class SourceQueueAdapter[T](delegate: SourceQueueWithComplete[T]) extends akka.stream.javadsl.SourceQueueWithComplete[T] {
  def offer(elem: T): CompletionStage[QueueOfferResult] = delegate.offer(elem).toJava
  def watchCompletion(): CompletionStage[Done] = delegate.watchCompletion().toJava
  def complete(): Unit = delegate.complete()
  def fail(ex: Throwable): Unit = delegate.fail(ex)
}
