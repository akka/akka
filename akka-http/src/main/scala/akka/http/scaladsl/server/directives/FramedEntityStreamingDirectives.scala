/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.scaladsl.server.directives

import akka.NotUsed
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.FramingWithContentType
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller, _ }
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.impl.ConstantFun
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

/**
 * Allows the [[MarshallingDirectives.entity]] directive to extract a `stream[T]` for framed messages.
 * See `JsonEntityStreamingSupport` and classes extending it, such as `SprayJsonSupport` to get marshallers.
 */
trait FramedEntityStreamingDirectives extends MarshallingDirectives {
  import FramedEntityStreamingDirectives._

  type RequestToSourceUnmarshaller[T] = FromRequestUnmarshaller[Source[T, NotUsed]]

  // TODO DOCS

  final def stream[T](implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    streamAsync(1)(um, framing)
  final def stream[T](framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): RequestToSourceUnmarshaller[T] =
    streamAsync(1)(um, framing)

  final def streamAsync[T](parallelism: Int)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    streamInternal[T](framing, (ec, mat) ⇒ Flow[ByteString].mapAsync(parallelism)(Unmarshal(_).to[T](um, ec, mat)))
  final def streamAsync[T](parallelism: Int, framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): RequestToSourceUnmarshaller[T] =
    streamAsync(parallelism)(um, framing)

  final def streamAsyncUnordered[T](parallelism: Int)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    streamInternal[T](framing, (ec, mat) ⇒ Flow[ByteString].mapAsyncUnordered(parallelism)(Unmarshal(_).to[T](um, ec, mat)))
  final def streamAsyncUnordered[T](parallelism: Int, framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): RequestToSourceUnmarshaller[T] =
    streamAsyncUnordered(parallelism)(um, framing)

  // TODO materialized value may want to be "drain/cancel" or something like it?
  // TODO could expose `streamMat`, for more fine grained picking of Marshaller

  // format: OFF
  private def streamInternal[T](framing: FramingWithContentType, marshalling: (ExecutionContext, Materializer) => Flow[ByteString, ByteString, NotUsed]#ReprMat[T, NotUsed]): RequestToSourceUnmarshaller[T] =
    Unmarshaller.withMaterializer[HttpRequest, Source[T, NotUsed]] { implicit ec ⇒ implicit mat ⇒ req ⇒
      val entity = req.entity
      if (!framing.supported(entity.contentType)) {
        val supportedContentTypes = framing.supported.map(ContentTypeRange(_))
        FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(supportedContentTypes))
      } else {
        val stream = entity.dataBytes.via(framing.flow).via(marshalling(ec, mat)).mapMaterializedValue(_ => NotUsed)  
        FastFuture.successful(stream)
      }
    }
  // format: ON

  // TODO note to self - we need the same of ease of streaming stuff for the client side - i.e. the twitter firehose case.

  implicit def _sourceMarshaller[T, M](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[Source[T, M]] =
    Marshaller[Source[T, M], HttpResponse] { implicit ec ⇒ source ⇒
      FastFuture successful {
        Marshalling.WithFixedContentType(mode.contentType, () ⇒ { // TODO charset?
          val bytes = source
            .mapAsync(1)(t ⇒ Marshal(t).to[HttpEntity])
            .map(_.dataBytes)
            .flatMapConcat(ConstantFun.scalaIdentityFunction)
            .intersperse(mode.start, mode.between, mode.end)
          HttpResponse(entity = HttpEntity(mode.contentType, bytes))
        }) :: Nil
      }
    }

  implicit def _sourceParallelismMarshaller[T](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[AsyncRenderingOf[T]] =
    Marshaller[AsyncRenderingOf[T], HttpResponse] { implicit ec ⇒ rendering ⇒
      FastFuture successful {
        Marshalling.WithFixedContentType(mode.contentType, () ⇒ { // TODO charset?
          val bytes = rendering.source
            .mapAsync(rendering.parallelism)(t ⇒ Marshal(t).to[HttpEntity])
            .map(_.dataBytes)
            .flatMapConcat(ConstantFun.scalaIdentityFunction)
            .intersperse(mode.start, mode.between, mode.end)
          HttpResponse(entity = HttpEntity(mode.contentType, bytes))
        }) :: Nil
      }
    }

  implicit def _sourceUnorderedMarshaller[T](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[AsyncUnorderedRenderingOf[T]] =
    Marshaller[AsyncUnorderedRenderingOf[T], HttpResponse] { implicit ec ⇒ rendering ⇒
      FastFuture successful {
        Marshalling.WithFixedContentType(mode.contentType, () ⇒ { // TODO charset?
          val bytes = rendering.source
            .mapAsync(rendering.parallelism)(t ⇒ Marshal(t).to[HttpEntity])
            .map(_.dataBytes)
            .flatMapConcat(ConstantFun.scalaIdentityFunction)
            .intersperse(mode.start, mode.between, mode.end)
          HttpResponse(entity = HttpEntity(mode.contentType, bytes))
        }) :: Nil
      }
    }

  // special rendering modes

  implicit def enableSpecialSourceRenderingModes[T](source: Source[T, Any]): EnableSpecialSourceRenderingModes[T] =
    new EnableSpecialSourceRenderingModes(source)

}
object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives {
  /**
   * Defines ByteStrings to be injected before the first, between, and after all elements of a [[Source]],
   * when used to complete a request.
   *
   * A typical example would be rendering a ``Source[T, _]`` as JSON array,
   * where start is `[`, between is `,`, and end is `]` - which procudes a valid json array, assuming each element can
   * be properly marshalled as JSON object.
   *
   * The corresponding values will typically be put into an [[Source.intersperse]] call on the to-be-rendered Source.
   */
  trait SourceRenderingMode extends akka.http.javadsl.server.directives.FramedEntityStreamingDirectives.SourceRenderingMode {
    override final def getContentType = contentType 
    def contentType: ContentType
  }

  final class AsyncRenderingOf[T](val source: Source[T, Any], val parallelism: Int)
  final class AsyncUnorderedRenderingOf[T](val source: Source[T, Any], val parallelism: Int)

}

final class EnableSpecialSourceRenderingModes[T](val source: Source[T, Any]) extends AnyVal {
  def renderAsync(parallelism: Int) = new FramedEntityStreamingDirectives.AsyncRenderingOf(source, parallelism)
  def renderAsyncUnordered(parallelism: Int) = new FramedEntityStreamingDirectives.AsyncUnorderedRenderingOf(source, parallelism)
}
