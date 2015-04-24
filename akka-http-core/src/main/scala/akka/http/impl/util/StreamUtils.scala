/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.io.InputStream
import java.util.concurrent.atomic.{ AtomicReference, AtomicBoolean }
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.{ SourceModule, SinkModule, ActorFlowMaterializerImpl, PublisherSink }
import akka.stream.scaladsl.FlexiMerge._
import org.reactivestreams.{ Subscription, Processor, Subscriber, Publisher }
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import akka.util.ByteString
import akka.http.scaladsl.model.RequestEntity
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._

/**
 * INTERNAL API
 */
private[http] object StreamUtils {
  import OperationAttributes.none

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   */
  def byteStringTransformer(f: ByteString ⇒ ByteString, finish: () ⇒ ByteString): Stage[ByteString, ByteString] = {
    new PushPullStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective =
        ctx.push(f(element))

      override def onPull(ctx: Context[ByteString]): SyncDirective =
        if (ctx.isFinishing) ctx.pushAndFinish(finish())
        else ctx.pull()

      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = ctx.absorbTermination()
    }
  }

  def failedPublisher[T](ex: Throwable): Publisher[T] =
    impl.ErrorPublisher(ex, "failed").asInstanceOf[Publisher[T]]

  def mapErrorTransformer(f: Throwable ⇒ Throwable): Flow[ByteString, ByteString, Unit] = {
    val transformer = new PushStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective =
        ctx.push(element)

      override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]): TerminationDirective =
        ctx.fail(f(cause))
    }

    Flow[ByteString].transform(() ⇒ transformer).named("transformError")
  }

  def sliceBytesTransformer(start: Long, length: Long): Flow[ByteString, ByteString, Unit] = {
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

  def limitByteChunksStage(maxBytesPerChunk: Int): PushPullStage[ByteString, ByteString] =
    new StatefulStage[ByteString, ByteString] {
      def initial = WaitingForData

      case object WaitingForData extends State {
        def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective =
          if (elem.size <= maxBytesPerChunk) ctx.push(elem)
          else {
            become(DeliveringData(elem.drop(maxBytesPerChunk)))
            ctx.push(elem.take(maxBytesPerChunk))
          }
      }

      case class DeliveringData(remaining: ByteString) extends State {
        def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective =
          throw new IllegalStateException("Not expecting data")

        override def onPull(ctx: Context[ByteString]): SyncDirective = {
          val toPush = remaining.take(maxBytesPerChunk)
          val toKeep = remaining.drop(maxBytesPerChunk)

          become {
            if (toKeep.isEmpty) WaitingForData
            else DeliveringData(toKeep)
          }
          if (ctx.isFinishing) ctx.pushAndFinish(toPush)
          else ctx.push(toPush)
        }
      }

      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective =
        current match {
          case WaitingForData    ⇒ ctx.finish()
          case _: DeliveringData ⇒ ctx.absorbTermination()
        }
    }

  /**
   * Applies a sequence of transformers on one source and returns a sequence of sources with the result. The input source
   * will only be traversed once.
   */
  def transformMultiple(input: Source[ByteString, Any], transformers: immutable.Seq[Flow[ByteString, ByteString, Any]])(implicit materializer: FlowMaterializer): immutable.Seq[Source[ByteString, Any]] =
    transformers match {
      case Nil      ⇒ Nil
      case Seq(one) ⇒ Vector(input.via(one))
      case multiple ⇒
        val (fanoutSub, fanoutPub) = Source.subscriber[ByteString].toMat(Sink.fanoutPublisher(16, 16))(Keep.both).run()
        val sources = transformers.map { flow ⇒
          // Doubly wrap to ensure that subscription to the running publisher happens before the final sources
          // are exposed, so there is no race
          Source(Source(fanoutPub).viaMat(flow)(Keep.right).runWith(Sink.publisher))
        }
        // The fanout publisher must be wired to the original source after all fanout subscribers have been subscribed
        input.runWith(Sink(fanoutSub))
        sources
    }

  def mapEntityError(f: Throwable ⇒ Throwable): RequestEntity ⇒ RequestEntity =
    _.transformDataBytes(mapErrorTransformer(f))

  /**
   * Simple blocking Source backed by an InputStream.
   *
   * FIXME: should be provided by akka-stream, see #15588
   */
  def fromInputStreamSource(inputStream: InputStream,
                            fileIODispatcher: String,
                            defaultChunkSize: Int = 65536): Source[ByteString, Unit] = {
    val onlyOnceFlag = new AtomicBoolean(false)

    val iterator = new Iterator[ByteString] {
      var finished = false
      if (onlyOnceFlag.get() || !onlyOnceFlag.compareAndSet(false, true))
        throw new IllegalStateException("One time source can only be instantiated once")

      def hasNext: Boolean = !finished

      def next(): ByteString =
        if (!finished) {
          val buffer = new Array[Byte](defaultChunkSize)
          val read = inputStream.read(buffer)
          if (read < 0) {
            finished = true
            inputStream.close()
            ByteString.empty
          } else ByteString.fromArray(buffer, 0, read)
        } else ByteString.empty
    }

    Source(() ⇒ iterator).withAttributes(ActorOperationAttributes.dispatcher(fileIODispatcher))
  }

  /**
   * Returns a source that can only be used once for testing purposes.
   */
  def oneTimeSource[T, Mat](other: Source[T, Mat], errorMsg: String = "One time source can only be instantiated once"): Source[T, Mat] = {
    val onlyOnceFlag = new AtomicBoolean(false)
    other.mapMaterialized { elem ⇒
      if (onlyOnceFlag.get() || !onlyOnceFlag.compareAndSet(false, true))
        throw new IllegalStateException(errorMsg)
      elem
    }
  }

  def oneTimePublisherSink[In](cell: OneTimeWriteCell[Publisher[In]], name: String): Sink[In, Publisher[In]] =
    new Sink[In, Publisher[In]](new OneTimePublisherSink(none, SinkShape(new Inlet(name)), cell))
  def oneTimeSubscriberSource[Out](cell: OneTimeWriteCell[Subscriber[Out]], name: String): Source[Out, Subscriber[Out]] =
    new Source[Out, Subscriber[Out]](new OneTimeSubscriberSource(none, SourceShape(new Outlet(name)), cell))

  /** A copy of PublisherSink that allows access to the publisher through the cell but can only materialized once */
  private class OneTimePublisherSink[In](attributes: OperationAttributes, shape: SinkShape[In], cell: OneTimeWriteCell[Publisher[In]])
    extends PublisherSink[In](attributes, shape) {
    override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
      val results = super.create(context)
      cell.set(results._2)
      results
    }
    override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
      new OneTimePublisherSink[In](attributes, shape, cell)

    override def withAttributes(attr: OperationAttributes): Module =
      new OneTimePublisherSink[In](attr, amendShape(attr), cell)
  }
  /** A copy of SubscriberSource that allows access to the subscriber through the cell but can only materialized once */
  private class OneTimeSubscriberSource[Out](val attributes: OperationAttributes, shape: SourceShape[Out], cell: OneTimeWriteCell[Subscriber[Out]])
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
    override def withAttributes(attr: OperationAttributes): Module =
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

  /** A merge for two streams that just forwards all elements and closes the connection eagerly. */
  class EagerCloseMerge2[T](name: String) extends FlexiMerge[T, FanInShape2[T, T, T]](new FanInShape2(name), OperationAttributes.name(name)) {
    def createMergeLogic(s: FanInShape2[T, T, T]): MergeLogic[T] =
      new MergeLogic[T] {
        def initialState: State[T] = State[T](ReadAny(s.in0, s.in1)) {
          case (ctx, port, in) ⇒ ctx.emit(in); SameState
        }

        override def initialCompletionHandling: CompletionHandling = eagerClose
      }
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
}

/**
 * INTERNAL API
 */
private[http] class EnhancedByteStringSource[Mat](val byteStringStream: Source[ByteString, Mat]) extends AnyVal {
  def join(implicit materializer: FlowMaterializer): Future[ByteString] =
    byteStringStream.runFold(ByteString.empty)(_ ++ _)
  def utf8String(implicit materializer: FlowMaterializer, ec: ExecutionContext): Future[String] =
    join.map(_.utf8String)
}