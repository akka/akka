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
  trait SourceCompletion {
    val completion = Promise[Unit]
  }

  type Offered = Promise[StreamCallbackStatus[Boolean]]

  val out = Outlet[T]("queueSource.out")
  override val shape: SourceShape[T] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val buffer = if (maxBuffer == 0) null else FixedSizeBuffer[T](maxBuffer)
    var pendingOffer: Option[(T, Offered)] = None
    var pulled = false

    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[(T, Offered)] with SourceCompletion {
      def bufferOverflowException = new Fail.BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")

      override def preStart(): Unit = initCallback(callback.invoke)
      override def postStop(): Unit = stopCallback {
        case (elem, promise) ⇒ promise.failure(new IllegalStateException("Stream is terminated. SourceQueue is detached"))
      }

      private def enqueueAndSuccess(elem: T, promise: Offered): Unit = {
        buffer.enqueue(elem)
        promise.success(StreamCallbackStatus.Success(true))
      }

      private def receiveElem(elem: T, promise: Offered): Unit = {
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
            promise.success(StreamCallbackStatus.Success(false))
          case Fail ⇒
            promise.success(StreamCallbackStatus.Failure(bufferOverflowException))
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
          receiveElem(elem, promise)
          if (pulled) {
            push(out, buffer.dequeue())
            pulled = false
          }
        } else if (pulled) {
          push(out, elem)
          pulled = false
          promise.success(StreamCallbackStatus.Success(true))
        } else pendingOffer = Some((elem, promise))
      })

      setHandler(out, new OutHandler {
        override def onDownstreamFinish(): Unit = {
          pendingOffer match {
            case Some((elem, promise)) ⇒
              promise.success(StreamCallbackStatus.StreamCompleted)
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
                promise.success(StreamCallbackStatus.Success(true))
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
      override def watchCompletion() = stageLogic.completion.future
      override def offer(element: T): Future[StreamCallbackStatus[Boolean]] = {
        val p = Promise[StreamCallbackStatus[Boolean]]()
        stageLogic.callback((element, p))
        p.future
      }
    })
  }
}

