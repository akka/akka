/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.OverflowStrategies._
import akka.stream._
import akka.stream.stage._
import scala.concurrent.{ Future, Promise }
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.Done
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

/**
 * INTERNAL API
 */
private[stream] object QueueSource {
  sealed trait Input[+T]
  final case class Offer[+T](elem: T, promise: Promise[QueueOfferResult]) extends Input[T]
  case object Completion extends Input[Nothing]
  final case class Failure(ex: Throwable) extends Input[Nothing]
}

/**
 * INTERNAL API
 */
final private[stream] class QueueSource[T](maxBuffer: Int, overflowStrategy: OverflowStrategy) extends GraphStageWithMaterializedValue[SourceShape[T], SourceQueueWithComplete[T]] {
  import QueueSource._

  val out = Outlet[T]("queueSource.out")
  override val shape: SourceShape[T] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val completion = Promise[Done]
    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[Input[T]] with OutHandler {
      var buffer: Buffer[T] = _
      var pendingOffer: Option[Offer[T]] = None
      var terminating = false

      override def preStart(): Unit = {
        if (maxBuffer > 0) buffer = Buffer(maxBuffer, materializer)
        initCallback(callback.invoke)
      }
      override def postStop(): Unit = stopCallback {
        case Offer(elem, promise) ⇒ promise.failure(new IllegalStateException("Stream is terminated. SourceQueue is detached"))
        case _                    ⇒ // ignore
      }

      private def enqueueAndSuccess(offer: Offer[T]): Unit = {
        buffer.enqueue(offer.elem)
        offer.promise.success(QueueOfferResult.Enqueued)
      }

      private def bufferElem(offer: Offer[T]): Unit = {
        if (!buffer.isFull) {
          enqueueAndSuccess(offer)
        } else overflowStrategy match {
          case DropHead ⇒
            buffer.dropHead()
            enqueueAndSuccess(offer)
          case DropTail ⇒
            buffer.dropTail()
            enqueueAndSuccess(offer)
          case DropBuffer ⇒
            buffer.clear()
            enqueueAndSuccess(offer)
          case DropNew ⇒
            offer.promise.success(QueueOfferResult.Dropped)
          case Fail ⇒
            val bufferOverflowException = new BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")
            offer.promise.success(QueueOfferResult.Failure(bufferOverflowException))
            completion.failure(bufferOverflowException)
            failStage(bufferOverflowException)
          case Backpressure ⇒
            pendingOffer match {
              case Some(_) ⇒
                offer.promise.failure(new IllegalStateException("You have to wait for previous offer to be resolved to send another request"))
              case None ⇒
                pendingOffer = Some(offer)
            }
        }
      }

      private val callback: AsyncCallback[Input[T]] = getAsyncCallback {

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
            case DropHead | DropBuffer ⇒
              pendingOffer.get.promise.success(QueueOfferResult.Dropped)
              pendingOffer = Some(offer)
            case DropTail | DropNew ⇒
              promise.success(QueueOfferResult.Dropped)
            case Fail ⇒
              val bufferOverflowException = new BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")
              promise.success(QueueOfferResult.Failure(bufferOverflowException))
              completion.failure(bufferOverflowException)
              failStage(bufferOverflowException)
            case Backpressure ⇒
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
          case Some(Offer(elem, promise)) ⇒
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
    }

    (stageLogic, new SourceQueueWithComplete[T] {
      override def watchCompletion() = completion.future
      override def offer(element: T): Future[QueueOfferResult] = {
        val p = Promise[QueueOfferResult]
        stageLogic.invoke(Offer(element, p))
        p.future
      }
      override def complete(): Unit = {
        stageLogic.invoke(Completion)
      }
      override def fail(ex: Throwable): Unit = {
        stageLogic.invoke(Failure(ex))
      }
    })
  }
}

private[akka] final class SourceQueueAdapter[T](delegate: SourceQueueWithComplete[T]) extends akka.stream.javadsl.SourceQueueWithComplete[T] {
  def offer(elem: T): CompletionStage[QueueOfferResult] = delegate.offer(elem).toJava
  def watchCompletion(): CompletionStage[Done] = delegate.watchCompletion().toJava
  def complete(): Unit = delegate.complete()
  def fail(ex: Throwable): Unit = delegate.fail(ex)
}
