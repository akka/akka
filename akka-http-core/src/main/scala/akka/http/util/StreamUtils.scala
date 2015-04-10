/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import akka.util.ByteString
import akka.http.model.RequestEntity
import akka.stream.{ FlowMaterializer, impl, OperationAttributes, ActorOperationAttributes }
import akka.stream.scaladsl._
import akka.stream.stage._

/**
 * INTERNAL API
 */
private[http] object StreamUtils {
  import OperationAttributes._

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
    import akka.stream.impl._

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
  def oneTimeSource[T, Mat](other: Source[T, Mat]): Source[T, Mat] = {
    val onlyOnceFlag = new AtomicBoolean(false)
    other.map { elem ⇒
      if (onlyOnceFlag.get() || !onlyOnceFlag.compareAndSet(false, true))
        throw new IllegalStateException("One time source can only be instantiated once")
      elem
    }
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
