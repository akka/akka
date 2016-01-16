/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.OverflowStrategies._
import akka.stream._
import akka.stream.stage._
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 */
private[akka] class QueueSource[T](maxBuffer: Int, overflowStrategy: OverflowStrategy) extends GraphStageWithMaterializedValue[SourceShape[T], SourceQueue[T]] {
  type Offered = Promise[QueueOfferResult]

  val out = Outlet[T]("queueSource.out")
  override val shape: SourceShape[T] = SourceShape.of(out)
  val completion = Promise[Unit]

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[(T, Offered)] {
      val buffer = if (maxBuffer == 0) null else FixedSizeBuffer[T](maxBuffer)
      var pendingOffer: Option[(T, Offered)] = None
      var pulled = false

      override def preStart(): Unit = initCallback(callback.invoke)
      override def postStop(): Unit = stopCallback {
        case (elem, promise) ⇒ promise.failure(new IllegalStateException("Stream is terminated. SourceQueue is detached"))
      }

      private def enqueueAndSuccess(elem: T, promise: Offered): Unit = {
        buffer.enqueue(elem)
        promise.success(QueueOfferResult.Enqueued)
      }

      private def bufferElem(elem: T, promise: Offered): Unit = {
        if (!buffer.isFull) {
          enqueueAndSuccess(elem, promise)
        } else overflowStrategy match {
          case DropHead ⇒
            buffer.dropHead()
            enqueueAndSuccess(elem, promise)
          case DropTail ⇒
            buffer.dropTail()
            enqueueAndSuccess(elem, promise)
          case DropBuffer ⇒
            buffer.clear()
            enqueueAndSuccess(elem, promise)
          case DropNew ⇒
            promise.success(QueueOfferResult.Dropped)
          case Fail ⇒
            val bufferOverflowException = new BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")
            promise.success(QueueOfferResult.Failure(bufferOverflowException))
            completion.failure(bufferOverflowException)
            failStage(bufferOverflowException)
          case Backpressure ⇒
            pendingOffer match {
              case Some(_) ⇒
                promise.failure(new IllegalStateException("You have to wait for previous offer to be resolved to send another request"))
              case None ⇒
                pendingOffer = Some((elem, promise))
            }
        }
      }

      private val callback: AsyncCallback[(T, Offered)] = getAsyncCallback(tuple ⇒ {
        val (elem, promise) = tuple

        if (maxBuffer != 0) {
          bufferElem(elem, promise)
          if (pulled) {
            push(out, buffer.dequeue())
            pulled = false
          }
        } else if (pulled) {
          push(out, elem)
          pulled = false
          promise.success(QueueOfferResult.Enqueued)
        } else pendingOffer = Some(tuple)
      })

      setHandler(out, new OutHandler {
        override def onDownstreamFinish(): Unit = {
          pendingOffer match {
            case Some((elem, promise)) ⇒
              promise.success(QueueOfferResult.QueueClosed)
              pendingOffer = None
            case None ⇒ // do nothing
          }
          completion.success(())
          completeStage()
        }

        override def onPull(): Unit = {
          if (maxBuffer == 0)
            pendingOffer match {
              case Some((elem, promise)) ⇒
                push(out, elem)
                promise.success(QueueOfferResult.Enqueued)
                pendingOffer = None
              case None ⇒ pulled = true
            }
          else if (!buffer.isEmpty) {
            push(out, buffer.dequeue())
            pendingOffer match {
              case Some((elem, promise)) ⇒
                enqueueAndSuccess(elem, promise)
                pendingOffer = None
              case None ⇒ //do nothing
            }
          } else pulled = true
        }
      })
    }

    (stageLogic, new SourceQueue[T] {
      override def watchCompletion() = completion.future
      override def offer(element: T): Future[QueueOfferResult] = {
        val p = Promise[QueueOfferResult]()
        stageLogic.invoke((element, p))
        p.future
      }
    })
  }
}

