/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.dispatch.ExecutionContexts
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.OverflowStrategies._
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._
import akka.stream.scaladsl.SourceQueueWithComplete
import scala.annotation.tailrec
import scala.concurrent.{ Future, Promise }
import akka.Done
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._
import scala.util.Try
import scala.util.control.NonFatal

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

/**
 * INTERNAL API
 */
private[stream] final class UnfoldResourceSource[T, S](
  create:   () ⇒ S,
  readData: (S) ⇒ Option[T],
  close:    (S) ⇒ Unit) extends GraphStage[SourceShape[T]] {
  val out = Outlet[T]("UnfoldResourceSource.out")
  override val shape = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSource

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
    var blockingStream: S = _
    setHandler(out, this)

    override def preStart(): Unit = blockingStream = create()

    @tailrec
    final override def onPull(): Unit = {
      var resumingMode = false
      try {
        readData(blockingStream) match {
          case Some(data) ⇒ push(out, data)
          case None       ⇒ closeStage()
        }
      } catch {
        case NonFatal(ex) ⇒ decider(ex) match {
          case Supervision.Stop ⇒
            close(blockingStream)
            failStage(ex)
          case Supervision.Restart ⇒
            restartState()
            resumingMode = true
          case Supervision.Resume ⇒
            resumingMode = true
        }
      }
      if (resumingMode) onPull()
    }

    override def onDownstreamFinish(): Unit = closeStage()

    private def restartState(): Unit = {
      close(blockingStream)
      blockingStream = create()
    }

    private def closeStage(): Unit =
      try {
        close(blockingStream)
        completeStage()
      } catch {
        case NonFatal(ex) ⇒ failStage(ex)
      }

  }
  override def toString = "UnfoldResourceSource"
}

private[stream] final class UnfoldResourceSourceAsync[T, S](
  create:   () ⇒ Future[S],
  readData: (S) ⇒ Future[Option[T]],
  close:    (S) ⇒ Future[Done]) extends GraphStage[SourceShape[T]] {
  val out = Outlet[T]("UnfoldResourceSourceAsync.out")
  override val shape = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSourceAsync

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
    var resource = Promise[S]()
    implicit val context = ExecutionContexts.sameThreadExecutionContext

    setHandler(out, this)

    override def preStart(): Unit = createStream(false)

    private def createStream(withPull: Boolean): Unit = {
      val cb = getAsyncCallback[Try[S]] {
        case scala.util.Success(res) ⇒
          resource.success(res)
          if (withPull) onPull()
        case scala.util.Failure(t) ⇒ failStage(t)
      }
      try {
        create().onComplete(cb.invoke)
      } catch {
        case NonFatal(ex) ⇒ failStage(ex)
      }
    }

    private def onResourceReady(f: (S) ⇒ Unit): Unit = resource.future.onSuccess {
      case resource ⇒ f(resource)
    }

    val errorHandler: PartialFunction[Throwable, Unit] = {
      case NonFatal(ex) ⇒ decider(ex) match {
        case Supervision.Stop ⇒
          onResourceReady(close(_))
          failStage(ex)
        case Supervision.Restart ⇒ restartState()
        case Supervision.Resume  ⇒ onPull()
      }
    }
    val callback = getAsyncCallback[Try[Option[T]]] {
      case scala.util.Success(data) ⇒ data match {
        case Some(d) ⇒ push(out, d)
        case None    ⇒ closeStage()
      }
      case scala.util.Failure(t) ⇒ errorHandler(t)
    }.invoke _

    final override def onPull(): Unit = onResourceReady {
      case resource ⇒
        try { readData(resource).onComplete(callback) } catch errorHandler
    }

    override def onDownstreamFinish(): Unit = closeStage()

    private def closeAndThen(f: () ⇒ Unit): Unit = {
      setKeepGoing(true)
      val cb = getAsyncCallback[Try[Done]] {
        case scala.util.Success(_) ⇒ f()
        case scala.util.Failure(t) ⇒ failStage(t)
      }

      onResourceReady(res ⇒
        try { close(res).onComplete(cb.invoke) } catch {
          case NonFatal(ex) ⇒ failStage(ex)
        })
    }
    private def restartState(): Unit = closeAndThen(() ⇒ {
      resource = Promise[S]()
      createStream(true)
    })
    private def closeStage(): Unit = closeAndThen(completeStage)

  }
  override def toString = "UnfoldResourceSourceAsync"

}