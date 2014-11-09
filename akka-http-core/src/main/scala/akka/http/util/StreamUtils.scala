/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream

import org.reactivestreams.{ Subscriber, Publisher }

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.Props
import akka.util.ByteString

import akka.stream.{ impl, Transformer, FlowMaterializer }
import akka.stream.scaladsl._

import akka.http.model.RequestEntity

/**
 * INTERNAL API
 */
private[http] object StreamUtils {
  /**
   * Maps a transformer by strictly applying the given function to each output element.
   */
  def mapTransformer[T, U, V](t: Transformer[T, U], f: U ⇒ V): Transformer[T, V] =
    new Transformer[T, V] {
      override def isComplete: Boolean = t.isComplete

      def onNext(element: T): immutable.Seq[V] = t.onNext(element).map(f)
      override def onTermination(e: Option[Throwable]): immutable.Seq[V] = t.onTermination(e).map(f)
      override def onError(cause: Throwable): Unit = t.onError(cause)
      override def cleanup(): Unit = t.cleanup()
    }

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   */
  def byteStringTransformer(f: ByteString ⇒ ByteString, finish: () ⇒ ByteString): Transformer[ByteString, ByteString] =
    new Transformer[ByteString, ByteString] {
      def onNext(element: ByteString): immutable.Seq[ByteString] = f(element) :: Nil

      override def onTermination(e: Option[Throwable]): immutable.Seq[ByteString] =
        if (e.isEmpty) {
          val last = finish()
          if (last.nonEmpty) last :: Nil
          else Nil
        } else super.onTermination(e)
    }

  def failedPublisher[T](ex: Throwable): Publisher[T] =
    impl.ErrorPublisher(ex).asInstanceOf[Publisher[T]]

  def mapErrorTransformer[T](f: Throwable ⇒ Throwable): Transformer[T, T] =
    new Transformer[T, T] {
      def onNext(element: T): immutable.Seq[T] = immutable.Seq(element)
      override def onError(cause: scala.Throwable): Unit = throw f(cause)
    }

  def sliceBytesTransformer(start: Long, length: Long): Transformer[ByteString, ByteString] =
    new Transformer[ByteString, ByteString] {
      type State = Transformer[ByteString, ByteString]

      def skipping = new State {
        var toSkip = start
        def onNext(element: ByteString): immutable.Seq[ByteString] =
          if (element.length < toSkip) {
            // keep skipping
            toSkip -= element.length
            Nil
          } else {
            become(taking(length))
            // toSkip <= element.length <= Int.MaxValue
            currentState.onNext(element.drop(toSkip.toInt))
          }
      }
      def taking(initiallyRemaining: Long) = new State {
        var remaining: Long = initiallyRemaining
        def onNext(element: ByteString): immutable.Seq[ByteString] = {
          val data = element.take(math.min(remaining, Int.MaxValue).toInt)
          remaining -= data.size
          if (remaining <= 0) become(finishing)
          data :: Nil
        }
      }
      def finishing = new State {
        override def isComplete: Boolean = true
        def onNext(element: ByteString): immutable.Seq[ByteString] =
          throw new IllegalStateException("onNext called on complete stream")
      }

      var currentState: State = if (start > 0) skipping else taking(length)
      def become(state: State): Unit = currentState = state

      override def isComplete: Boolean = currentState.isComplete
      def onNext(element: ByteString): immutable.Seq[ByteString] = currentState.onNext(element)
      override def onTermination(e: Option[Throwable]): immutable.Seq[ByteString] = currentState.onTermination(e)
    }

  /**
   * Applies a sequence of transformers on one source and returns a sequence of sources with the result. The input source
   * will only be traversed once.
   */
  def transformMultiple[T, U](input: Source[T], transformers: immutable.Seq[() ⇒ Transformer[T, U]])(implicit materializer: FlowMaterializer): immutable.Seq[Source[U]] =
    transformers match {
      case Nil      ⇒ Nil
      case Seq(one) ⇒ Vector(input.transform("transformMultipleElement", one))
      case multiple ⇒
        val results = Vector.fill(multiple.size)(Sink.publisher[U])
        val mat =
          FlowGraph { implicit b ⇒
            import FlowGraphImplicits._

            val broadcast = Broadcast[T]("transformMultipleInputBroadcast")
            input ~> broadcast
            (multiple, results).zipped.foreach { (trans, sink) ⇒
              broadcast ~> Flow[T].transform("transformMultipleElement", trans) ~> sink
            }
          }.run()
        results.map(s ⇒ Source(mat.get(s)))
    }

  def mapEntityError(f: Throwable ⇒ Throwable): RequestEntity ⇒ RequestEntity =
    _.transformDataBytes(() ⇒ mapErrorTransformer(f))

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
        } else (ErrorPublisher(new IllegalStateException("One time source can only be instantiated once")).asInstanceOf[Publisher[ByteString]], ())
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
        else (ErrorPublisher(new IllegalStateException("One time source can only be instantiated once")).asInstanceOf[Publisher[T]], ())
    }
  }
}

/**
 * INTERNAL API
 */
private[http] class EnhancedTransformer[T, U](val t: Transformer[T, U]) extends AnyVal {
  def map[V](f: U ⇒ V): Transformer[T, V] = StreamUtils.mapTransformer(t, f)
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
