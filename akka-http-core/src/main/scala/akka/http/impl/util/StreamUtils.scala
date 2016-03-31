/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import akka.NotUsed
import akka.http.scaladsl.model.RequestEntity
import akka.stream._
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.impl.{ PublisherSink, SinkModule, SourceModule }
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }
import scala.concurrent.{ ExecutionContext, Future, Promise }

/**
 * INTERNAL API
 */
private[http] object StreamUtils {
  import Attributes.none

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   * Empty ByteStrings are discarded.
   */
  def byteStringTransformer(f: ByteString ⇒ ByteString, finish: () ⇒ ByteString): Stage[ByteString, ByteString] = {
    new PushPullStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective = {
        val data = f(element)
        if (data.nonEmpty) ctx.push(data)
        else ctx.pull()
      }

      override def onPull(ctx: Context[ByteString]): SyncDirective =
        if (ctx.isFinishing) {
          val data = finish()
          if (data.nonEmpty) ctx.pushAndFinish(data)
          else ctx.finish()
        } else ctx.pull()

      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = ctx.absorbTermination()
    }
  }

  def failedPublisher[T](ex: Throwable): Publisher[T] =
    impl.ErrorPublisher(ex, "failed").asInstanceOf[Publisher[T]]

  def mapErrorTransformer(f: Throwable ⇒ Throwable): Flow[ByteString, ByteString, NotUsed] = {
    val transformer = new PushStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective =
        ctx.push(element)

      override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]): TerminationDirective =
        ctx.fail(f(cause))
    }

    Flow[ByteString].transform(() ⇒ transformer).named("transformError")
  }

  def captureTermination[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Future[Unit]) = {
    val promise = Promise[Unit]()
    val transformer = new PushStage[T, T] {
      def onPush(element: T, ctx: Context[T]) = ctx.push(element)
      override def onUpstreamFailure(cause: Throwable, ctx: Context[T]) = {
        promise.failure(cause)
        ctx.fail(cause)
      }
      override def postStop(): Unit = {
        promise.trySuccess(())
      }
    }
    source.transform(() ⇒ transformer) -> promise.future
  }

  def sliceBytesTransformer(start: Long, length: Long): Flow[ByteString, ByteString, NotUsed] = {
    val transformer = new StatefulStage[ByteString, ByteString] {

      def skipping = new State {
        var toSkip = start

        override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective =
          if (element.length < toSkip) {
            // keep skipping
            toSkip -= element.length
            ctx.pull()
          } else {
            become(taking(length))
            // toSkip <= element.length <= Int.MaxValue
            current.onPush(element.drop(toSkip.toInt), ctx)
          }
      }

      def taking(initiallyRemaining: Long) = new State {
        var remaining: Long = initiallyRemaining

        override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective = {
          val data = element.take(math.min(remaining, Int.MaxValue).toInt)
          remaining -= data.size
          if (remaining <= 0) ctx.pushAndFinish(data)
          else ctx.push(data)
        }
      }

      override def initial: State = if (start > 0) skipping else taking(length)
    }
    Flow[ByteString].transform(() ⇒ transformer).named("sliceBytes")
  }

  def limitByteChunksStage(maxBytesPerChunk: Int): GraphStage[FlowShape[ByteString, ByteString]] =
    new SimpleLinearGraphStage[ByteString] {
      override def initialAttributes = Attributes.name("limitByteChunksStage")

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

        var remaining = ByteString.empty

        def splitAndPush(elem: ByteString): Unit = {
          val toPush = remaining.take(maxBytesPerChunk)
          val toKeep = remaining.drop(maxBytesPerChunk)
          push(out, toPush)
          remaining = toKeep
        }
        setHandlers(in, out, WaitingForData)

        case object WaitingForData extends InHandler with OutHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            if (elem.size <= maxBytesPerChunk) push(out, elem)
            else {
              splitAndPush(elem)
              setHandlers(in, out, DeliveringData)
            }
          }
          override def onPull(): Unit = pull(in)
        }

        case object DeliveringData extends InHandler() with OutHandler {
          var finishing = false
          override def onPush(): Unit = throw new IllegalStateException("Not expecting data")
          override def onPull(): Unit = {
            splitAndPush(remaining)
            if (remaining.isEmpty) {
              if (finishing) completeStage() else setHandlers(in, out, WaitingForData)
            }
          }
          override def onUpstreamFinish(): Unit = if (remaining.isEmpty) completeStage() else finishing = true
        }

        override def toString = "limitByteChunksStage"
      }
    }

  def mapEntityError(f: Throwable ⇒ Throwable): RequestEntity ⇒ RequestEntity =
    _.transformDataBytes(mapErrorTransformer(f))

  /**
   * Returns a source that can only be used once for testing purposes.
   */
  def oneTimeSource[T, Mat](other: Source[T, Mat], errorMsg: String = "One time source can only be instantiated once"): Source[T, Mat] = {
    val onlyOnceFlag = new AtomicBoolean(false)
    other.mapMaterializedValue { elem ⇒
      if (onlyOnceFlag.get() || !onlyOnceFlag.compareAndSet(false, true))
        throw new IllegalStateException(errorMsg)
      elem
    }
  }

  def oneTimePublisherSink[In](cell: OneTimeWriteCell[Publisher[In]], name: String): Sink[In, Publisher[In]] =
    new Sink[In, Publisher[In]](new OneTimePublisherSink(none, SinkShape(Inlet(name)), cell))
  def oneTimeSubscriberSource[Out](cell: OneTimeWriteCell[Subscriber[Out]], name: String): Source[Out, Subscriber[Out]] =
    new Source[Out, Subscriber[Out]](new OneTimeSubscriberSource(none, SourceShape(Outlet(name)), cell))

  /** A copy of PublisherSink that allows access to the publisher through the cell but can only materialized once */
  private class OneTimePublisherSink[In](attributes: Attributes, shape: SinkShape[In], cell: OneTimeWriteCell[Publisher[In]])
      extends PublisherSink[In](attributes, shape) {
    override def create(context: MaterializationContext): (AnyRef, Publisher[In]) = {
      val results = super.create(context)
      cell.set(results._2)
      results
    }
    override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
      new OneTimePublisherSink[In](attributes, shape, cell)

    override def withAttributes(attr: Attributes): OneTimePublisherSink[In] =
      new OneTimePublisherSink[In](attr, amendShape(attr), cell)
  }
  /** A copy of SubscriberSource that allows access to the subscriber through the cell but can only materialized once */
  private class OneTimeSubscriberSource[Out](val attributes: Attributes, shape: SourceShape[Out], cell: OneTimeWriteCell[Subscriber[Out]])
      extends SourceModule[Out, Subscriber[Out]](shape) {

    override def create(context: MaterializationContext): (Publisher[Out], Subscriber[Out]) = {
      val processor = new Processor[Out, Out] {
        @volatile private var subscriber: Subscriber[_ >: Out] = null

        override def subscribe(s: Subscriber[_ >: Out]): Unit = subscriber = s

        override def onError(t: Throwable): Unit = subscriber.onError(t)
        override def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)
        override def onComplete(): Unit = subscriber.onComplete()
        override def onNext(t: Out): Unit = subscriber.onNext(t)
      }
      cell.setValue(processor)

      (processor, processor)
    }

    override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Subscriber[Out]] =
      new OneTimeSubscriberSource[Out](attributes, shape, cell)
    override def withAttributes(attr: Attributes): OneTimeSubscriberSource[Out] =
      new OneTimeSubscriberSource[Out](attr, amendShape(attr), cell)
  }

  trait ReadableCell[+T] {
    def value: T
  }
  /** A one time settable cell */
  class OneTimeWriteCell[T <: AnyRef] extends AtomicReference[T] with ReadableCell[T] {
    def value: T = {
      val value = get()
      require(value != null, "Value wasn't set yet")
      value
    }

    def setValue(value: T): Unit =
      if (!compareAndSet(null.asInstanceOf[T], value))
        throw new IllegalStateException("Value can be only set once.")
  }

  // TODO: remove after #16394 is cleared
  def recover[A, B >: A](pf: PartialFunction[Throwable, B]): () ⇒ PushPullStage[A, B] = {
    val stage = new PushPullStage[A, B] {
      var recovery: Option[B] = None
      def onPush(elem: A, ctx: Context[B]): SyncDirective = ctx.push(elem)
      def onPull(ctx: Context[B]): SyncDirective = recovery match {
        case None    ⇒ ctx.pull()
        case Some(x) ⇒ { recovery = null; ctx.push(x) }
        case null    ⇒ ctx.finish()
      }
      override def onUpstreamFailure(cause: Throwable, ctx: Context[B]): TerminationDirective =
        if (pf isDefinedAt cause) {
          recovery = Some(pf(cause))
          ctx.absorbTermination()
        } else super.onUpstreamFailure(cause, ctx)
    }
    () ⇒ stage
  }

  /**
   * Returns a no-op flow that materializes to a future that will be completed when the flow gets a
   * completion or error signal. It doesn't necessarily mean, though, that all of a streaming pipeline
   * is finished, only that the part that contains this flow has finished work.
   */
  def identityFinishReporter[T]: Flow[T, T, Future[Unit]] = {
    // copy from Sink.foreach
    def newForeachStage(): (PushStage[T, T], Future[Unit]) = {
      val promise = Promise[Unit]()

      val stage = new PushStage[T, T] {
        override def onPush(elem: T, ctx: Context[T]): SyncDirective = ctx.push(elem)

        override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
          promise.failure(cause)
          ctx.fail(cause)
        }

        override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
          promise.success(())
          ctx.finish()
        }

        override def onDownstreamFinish(ctx: Context[T]): TerminationDirective = {
          promise.success(())
          ctx.finish()
        }

        override def decide(cause: Throwable): Supervision.Directive = {
          // supervision will be implemented by #16916
          promise.tryFailure(cause)
          super.decide(cause)
        }
      }

      (stage, promise.future)
    }
    Flow[T].transformMaterializing(newForeachStage)
  }

  /**
   * Similar to Source.maybe but doesn't rely on materialization. Can only be used once.
   */
  trait OneTimeValve {
    def source[T]: Source[T, NotUsed]
    def open(): Unit
  }
  object OneTimeValve {
    def apply(): OneTimeValve = new OneTimeValve {
      val promise = Promise[Unit]()
      val _source = Source.fromFuture(promise.future).drop(1) // we are only interested in the completion event

      def source[T]: Source[T, NotUsed] = _source.asInstanceOf[Source[T, NotUsed]] // safe, because source won't generate any elements
      def open(): Unit = promise.success(())
    }
  }
}

/**
 * INTERNAL API
 */
private[http] class EnhancedByteStringSource[Mat](val byteStringStream: Source[ByteString, Mat]) extends AnyVal {
  def join(implicit materializer: Materializer): Future[ByteString] =
    byteStringStream.runFold(ByteString.empty)(_ ++ _)
  def utf8String(implicit materializer: Materializer, ec: ExecutionContext): Future[String] =
    join.map(_.utf8String)
}
