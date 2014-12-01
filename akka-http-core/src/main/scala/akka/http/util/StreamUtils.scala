/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import java.io.InputStream

import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import akka.actor.Props
import akka.http.model.RequestEntity
import akka.stream.FlowMaterializer
import akka.stream.impl.Ast.AstNode
import akka.stream.impl.Ast.StageFactory
import akka.stream.impl.fusing.IteratorInterpreter
import akka.stream.scaladsl._
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.stage._
import akka.stream.impl
import akka.util.ByteString
import org.reactivestreams.{ Subscriber, Publisher }

/**
 * INTERNAL API
 */
private[http] object StreamUtils {

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   */
  def byteStringTransformer(f: ByteString ⇒ ByteString, finish: () ⇒ ByteString): Flow[ByteString, ByteString] = {
    val transformer = new PushPullStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): Directive =
        ctx.push(f(element))

      override def onPull(ctx: Context[ByteString]): Directive =
        if (ctx.isFinishing) ctx.pushAndFinish(finish())
        else ctx.pull()

      override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = ctx.absorbTermination()
    }
    Flow[ByteString].section(name("transformBytes"))(_.transform(() ⇒ transformer))
  }

  def failedPublisher[T](ex: Throwable): Publisher[T] =
    impl.ErrorPublisher(ex, "failed").asInstanceOf[Publisher[T]]

  def mapErrorTransformer(f: Throwable ⇒ Throwable): Flow[ByteString, ByteString] = {
    val transformer = new PushStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): Directive =
        ctx.push(element)

      override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]): TerminationDirective =
        ctx.fail(f(cause))
    }

    Flow[ByteString].section(name("transformError"))(_.transform(() ⇒ transformer))
  }

  def sliceBytesTransformer(start: Long, length: Long): Flow[ByteString, ByteString] = {
    val transformer = new StatefulStage[ByteString, ByteString] {

      def skipping = new State {
        var toSkip = start
        override def onPush(element: ByteString, ctx: Context[ByteString]): Directive =
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
        override def onPush(element: ByteString, ctx: Context[ByteString]): Directive = {
          val data = element.take(math.min(remaining, Int.MaxValue).toInt)
          remaining -= data.size
          if (remaining <= 0) ctx.pushAndFinish(data)
          else ctx.push(data)
        }
      }

      override def initial: State = if (start > 0) skipping else taking(length)
    }
    Flow[ByteString].section(name("sliceBytes"))(_.transform(() ⇒ transformer))
  }

  /**
   * Applies a sequence of transformers on one source and returns a sequence of sources with the result. The input source
   * will only be traversed once.
   */
  def transformMultiple(input: Source[ByteString], transformers: immutable.Seq[Flow[ByteString, ByteString]])(implicit materializer: FlowMaterializer): immutable.Seq[Source[ByteString]] =
    transformers match {
      case Nil      ⇒ Nil
      case Seq(one) ⇒ Vector(input.via(one))
      case multiple ⇒
        val results = Vector.fill(multiple.size)(Sink.publisher[ByteString])
        val mat =
          FlowGraph { implicit b ⇒
            import FlowGraphImplicits._

            val broadcast = Broadcast[ByteString]("transformMultipleInputBroadcast")
            input ~> broadcast
            (multiple, results).zipped.foreach { (trans, sink) ⇒
              broadcast ~> trans ~> sink
            }
          }.run()
        results.map(s ⇒ Source(mat.get(s)))
    }

  def mapEntityError(f: Throwable ⇒ Throwable): RequestEntity ⇒ RequestEntity =
    _.transformDataBytes(mapErrorTransformer(f))

  /**
   * Simple blocking Source backed by an InputStream.
   *
   * FIXME: should be provided by akka-stream, see #15588
   */
  def fromInputStreamSource(inputStream: InputStream, defaultChunkSize: Int = 65536): Source[ByteString] = {
    import akka.stream.impl._

    def props(materializer: ActorBasedFlowMaterializer): Props = {
      val iterator = new Iterator[ByteString] {
        var finished = false
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

      IteratorPublisher.props(iterator, materializer.settings).withDispatcher(materializer.settings.fileIODispatcher)
    }

    new AtomicBoolean(false) with SimpleActorFlowSource[ByteString] {
      override def attach(flowSubscriber: Subscriber[ByteString], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
        create(materializer, flowName)._1.subscribe(flowSubscriber)

      override def isActive: Boolean = true
      override def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Publisher[ByteString], Unit) =
        if (!getAndSet(true)) {
          val ref = materializer.actorOf(props(materializer), name = s"$flowName-0-InputStream-source")
          val publisher = ActorPublisher[ByteString](ref)
          ref ! ExposedPublisher(publisher.asInstanceOf[impl.ActorPublisher[Any]])

          (publisher, ())
        } else (ErrorPublisher(new IllegalStateException("One time source can only be instantiated once"), "failed").asInstanceOf[Publisher[ByteString]], ())
    }
  }

  /**
   * Returns a source that can only be used once for testing purposes.
   */
  def oneTimeSource[T](other: Source[T]): Source[T] = {
    import akka.stream.impl._
    val original = other.asInstanceOf[ActorFlowSource[T]]
    new AtomicBoolean(false) with SimpleActorFlowSource[T] {
      override def attach(flowSubscriber: Subscriber[T], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
        create(materializer, flowName)._1.subscribe(flowSubscriber)
      override def isActive: Boolean = true
      override def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Publisher[T], Unit) =
        if (!getAndSet(true)) (original.create(materializer, flowName)._1, ())
        else (ErrorPublisher(new IllegalStateException("One time source can only be instantiated once"), "failed").asInstanceOf[Publisher[T]], ())
    }
  }

  def runStrict(sourceData: ByteString, transformer: Flow[ByteString, ByteString], maxByteSize: Long, maxElements: Int): Try[Option[ByteString]] =
    Try {
      transformer match {
        // FIXME #16382 right now the flow can't use keys, should that be allowed?
        case Pipe(ops, keys, _) if keys.isEmpty ⇒
          if (ops.isEmpty)
            Some(sourceData)
          else {
            @tailrec def tryBuild(remaining: List[AstNode], acc: List[PushPullStage[ByteString, ByteString]]): List[PushPullStage[ByteString, ByteString]] =
              remaining match {
                case Nil ⇒ acc.reverse
                case StageFactory(mkStage, _) :: tail ⇒
                  mkStage() match {
                    case d: PushPullStage[ByteString, ByteString] ⇒
                      tryBuild(tail, d :: acc)
                    case _ ⇒ Nil
                  }
                case _ ⇒ Nil
              }

            val strictOps = tryBuild(ops, Nil)
            if (strictOps.isEmpty)
              None
            else {
              val iter: Iterator[ByteString] = new IteratorInterpreter(Iterator.single(sourceData), strictOps).iterator
              var byteSize = 0L
              var result = ByteString.empty
              var i = 0
              // note that iter.next() will throw exception if the stream fails, caught by the enclosing Try
              while (iter.hasNext) {
                i += 1
                if (i > maxElements)
                  throw new IllegalArgumentException(s"Too many elements produced by byte transformation, $i was greater than max allowed $maxElements elements")
                val elem = iter.next()
                byteSize += elem.size
                if (byteSize > maxByteSize)
                  throw new IllegalArgumentException(s"Too large data result, $byteSize bytes was greater than max allowed $maxByteSize bytes")
                result ++= elem
              }
              Some(result)
            }
          }

        case _ ⇒ None
      }
    }

}

/**
 * INTERNAL API
 */
private[http] class EnhancedByteStringSource(val byteStringStream: Source[ByteString]) extends AnyVal {
  def join(implicit materializer: FlowMaterializer): Future[ByteString] =
    byteStringStream.fold(ByteString.empty)(_ ++ _)
  def utf8String(implicit materializer: FlowMaterializer, ec: ExecutionContext): Future[String] =
    join.map(_.utf8String)
}
