/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import akka.http.model.RequestEntity
import akka.stream.impl.ErrorPublisher
import akka.stream.Transformer
import akka.util.ByteString
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import akka.stream.scaladsl2.FlowMaterializer
import akka.stream.scaladsl2.Source

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
    ErrorPublisher(ex).asInstanceOf[Publisher[T]]

  def mapErrorTransformer[T](f: Throwable ⇒ Throwable): Transformer[T, T] =
    new Transformer[T, T] {
      def onNext(element: T): immutable.Seq[T] = immutable.Seq(element)
      override def onError(cause: scala.Throwable): Unit = throw f(cause)
    }

  def mapEntityError(f: Throwable ⇒ Throwable): RequestEntity ⇒ RequestEntity =
    _.transformDataBytes(() ⇒ mapErrorTransformer(f))
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
private[http] class EnhancedByteStringPublisher(val byteStringStream: Publisher[ByteString]) extends AnyVal {
  // FIXME remove this implicit conversion?
  def join(implicit materializer: FlowMaterializer): Future[ByteString] =
    Source(byteStringStream).fold(ByteString.empty)(_ ++ _)
  def utf8String(implicit materializer: FlowMaterializer, ec: ExecutionContext): Future[String] =
    join.map(_.utf8String)
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
