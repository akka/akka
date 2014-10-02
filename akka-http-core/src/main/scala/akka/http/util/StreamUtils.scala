/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import akka.stream.impl.ErrorPublisher
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, Transformer }
import akka.util.ByteString
import org.reactivestreams.Publisher

import scala.collection.immutable

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
}

private[http] class EnhancedTransformer[T, U](val t: Transformer[T, U]) extends AnyVal {
  def map[V](f: U ⇒ V): Transformer[T, V] = StreamUtils.mapTransformer(t, f)
}