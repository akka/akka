/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.scaladsl.server.directives

import akka.NotUsed
import akka.http.scaladsl.common.{ FramingWithContentType, SourceRenderingMode }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
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

  final def asSourceOf[T](implicit um: Unmarshaller[HttpEntity, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    asSourceOfAsync(1)(um, framing)
  final def asSourceOf[T](framing: FramingWithContentType)(implicit um: Unmarshaller[HttpEntity, T]): RequestToSourceUnmarshaller[T] =
    asSourceOfAsync(1)(um, framing)

  final def asSourceOfAsync[T](parallelism: Int)(implicit um: Unmarshaller[HttpEntity, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    asSourceOfInternal[T](framing, (ec, mat) ⇒ Flow[HttpEntity].mapAsync(parallelism)(Unmarshal(_).to[T](um, ec, mat)))
  final def asSourceOfAsync[T](parallelism: Int, framing: FramingWithContentType)(implicit um: Unmarshaller[HttpEntity, T]): RequestToSourceUnmarshaller[T] =
    asSourceOfAsync(parallelism)(um, framing)

  final def asSourceOfAsyncUnordered[T](parallelism: Int)(implicit um: Unmarshaller[HttpEntity, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    asSourceOfInternal[T](framing, (ec, mat) ⇒ Flow[HttpEntity].mapAsyncUnordered(parallelism)(Unmarshal(_).to[T](um, ec, mat)))
  final def asSourceOfAsyncUnordered[T](parallelism: Int, framing: FramingWithContentType)(implicit um: Unmarshaller[HttpEntity, T]): RequestToSourceUnmarshaller[T] =
    asSourceOfAsyncUnordered(parallelism)(um, framing)

  // format: OFF
  private def asSourceOfInternal[T](framing: FramingWithContentType, marshalling: (ExecutionContext, Materializer) => Flow[HttpEntity, ByteString, NotUsed]#ReprMat[T, NotUsed]): RequestToSourceUnmarshaller[T] =
    Unmarshaller.withMaterializer[HttpRequest, Source[T, NotUsed]] { implicit ec ⇒ implicit mat ⇒ req ⇒
      val entity = req.entity
      if (!framing.matches(entity.contentType)) {
        val supportedContentTypes = framing.supported
        FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(supportedContentTypes))
      } else {
//        val stream = entity.dataBytes.via(framing.flow).via(marshalling(ec, mat)).mapMaterializedValue(_ => NotUsed)  
        val stream = Source.single(entity.transformDataBytes(framing.flow)).via(marshalling(ec, mat)).mapMaterializedValue(_ => NotUsed)  
        FastFuture.successful(stream)
      }
    }
  // format: ON

  // TODO note to self - we need the same of ease of streaming stuff for the client side - i.e. the twitter firehose case.

  implicit def _sourceMarshaller[T, M](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[Source[T, M]] =
    Marshaller[Source[T, M], HttpResponse] { implicit ec ⇒ source ⇒
      FastFuture successful {
        Marshalling.WithFixedContentType(mode.contentType, () ⇒ {
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
        Marshalling.WithFixedContentType(mode.contentType, () ⇒ {
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
        Marshalling.WithFixedContentType(mode.contentType, () ⇒ {
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

  implicit def _enableSpecialSourceRenderingModes[T](source: Source[T, Any]): EnableSpecialSourceRenderingModes[T] =
    new EnableSpecialSourceRenderingModes(source)

}
object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives {
  sealed class AsyncSourceRenderingMode
  final class AsyncRenderingOf[T](val source: Source[T, Any], val parallelism: Int) extends AsyncSourceRenderingMode
  final class AsyncUnorderedRenderingOf[T](val source: Source[T, Any], val parallelism: Int) extends AsyncSourceRenderingMode

}

final class EnableSpecialSourceRenderingModes[T](val source: Source[T, Any]) extends AnyVal {
  /**
   * Causes the response stream to be marshalled asynchronously (up to `parallelism` elements at once),
   * while retaining the ordering of incoming elements.
   *
   * See also [[Source.mapAsync]].
   */
  def renderAsync(parallelism: Int) = new FramedEntityStreamingDirectives.AsyncRenderingOf(source, parallelism)
  /**
   * Causes the response stream to be marshalled asynchronously (up to `parallelism` elements at once),
   * emitting the first one that finished marshalling onto the wire.
   *
   * This sacrifices ordering of the incoming data in regards to data actually rendered onto the wire,
   * but may be faster if some elements are smaller than other ones by not stalling the small elements
   * from being written while the large one still is being marshalled.
   *
   * See also [[Source.mapAsyncUnordered]].
   */
  def renderAsyncUnordered(parallelism: Int) = new FramedEntityStreamingDirectives.AsyncUnorderedRenderingOf(source, parallelism)
}
