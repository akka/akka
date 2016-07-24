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
import akka.stream.scaladsl.{ Flow, Keep, Source }
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

/**
 * Allows the [[MarshallingDirectives.entity]] directive to extract a [[Source]] of elements.
 *
 * See [[akka.http.scaladsl.server.EntityStreamingSupport]] for useful default [[FramingWithContentType]] instances and
 * support traits such as `SprayJsonSupport` (or your other favourite JSON library) to provide the needed [[Marshaller]] s.
 */
trait FramedEntityStreamingDirectives extends MarshallingDirectives {
  import FramedEntityStreamingDirectives._

  type RequestToSourceUnmarshaller[T] = FromRequestUnmarshaller[Source[T, NotUsed]]

  /**
   * Extracts entity as [[Source]] of elements of type `T`.
   * This is achieved by applying the implicitly provided (in the following order):
   *
   * - 1st: [[FramingWithContentType]] in order to chunk-up the incoming [[ByteString]]s according to the
   *        `Content-Type` aware framing (for example, [[akka.http.scaladsl.server.EntityStreamingSupport.bracketCountingJsonFraming]]).
   * - 2nd: [[Unmarshaller]] (from [[ByteString]] to `T`) for each of the respective "chunks" (e.g. for each JSON element contained within an array).
   *
   * The request will be rejected with an [[akka.http.scaladsl.server.UnsupportedRequestContentTypeRejection]] if
   * its [[ContentType]] is not supported by the used `framing` or `unmarshaller`.
   *
   * It is recommended to use the [[akka.http.scaladsl.server.EntityStreamingSupport]] trait in conjunction with this
   * directive as it helps provide the right [[FramingWithContentType]] and [[SourceRenderingMode]] for the most
   * typical usage scenarios (JSON, CSV, ...).
   *
   * Cancelling extracted [[Source]] closes the connection abruptly (same as cancelling the `entity.dataBytes`).
   *
   * If looking to improve marshalling performance in face of many elements (possibly of different sizes),
   * you may be interested in using [[asSourceOfAsyncUnordered]] instead.
   *
   * See also [[MiscDirectives.withoutSizeLimit]] as you may want to allow streaming infinite streams of data in this route.
   * By default the uploaded data is limited by the `akka.http.parsing.max-content-length`.
   */
  final def asSourceOf[T](implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    asSourceOfAsync(1)(um, framing)

  /**
   * Extracts entity as [[Source]] of elements of type `T`.
   * This is achieved by applying the implicitly provided (in the following order):
   *
   * - 1st: [[FramingWithContentType]] in order to chunk-up the incoming [[ByteString]]s according to the
   *        `Content-Type` aware framing (for example, [[akka.http.scaladsl.server.EntityStreamingSupport.bracketCountingJsonFraming]]).
   * - 2nd: [[Unmarshaller]] (from [[ByteString]] to `T`) for each of the respective "chunks" (e.g. for each JSON element contained within an array).
   *
   * The request will be rejected with an [[akka.http.scaladsl.server.UnsupportedRequestContentTypeRejection]] if
   * its [[ContentType]] is not supported by the used `framing` or `unmarshaller`.
   *
   * It is recommended to use the [[akka.http.scaladsl.server.EntityStreamingSupport]] trait in conjunction with this
   * directive as it helps provide the right [[FramingWithContentType]] and [[SourceRenderingMode]] for the most
   * typical usage scenarios (JSON, CSV, ...).
   *
   * Cancelling extracted [[Source]] closes the connection abruptly (same as cancelling the `entity.dataBytes`).
   *
   * If looking to improve marshalling performance in face of many elements (possibly of different sizes),
   * you may be interested in using [[asSourceOfAsyncUnordered]] instead.
   *
   * See also [[MiscDirectives.withoutSizeLimit]] as you may want to allow streaming infinite streams of data in this route.
   * By default the uploaded data is limited by the `akka.http.parsing.max-content-length`.
   */
  final def asSourceOf[T](framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): RequestToSourceUnmarshaller[T] =
    asSourceOfAsync(1)(um, framing)

  /**
   * Similar to [[asSourceOf]] however will apply at most `parallelism` unmarshallers in parallel.
   *
   * The source elements emitted preserve the order in which they are sent in the incoming [[HttpRequest]].
   * If you want to sacrivice ordering in favour of (potential) slight performance improvements in reading the input
   * you may want to use [[asSourceOfAsyncUnordered]] instead, which lifts the ordering guarantee.
   *
   * Refer to [[asSourceOf]] for more in depth-documentation and guidelines.
   *
   * If looking to improve marshalling performance in face of many elements (possibly of different sizes),
   * you may be interested in using [[asSourceOfAsyncUnordered]] instead.
   *
   * See also [[MiscDirectives.withoutSizeLimit]] as you may want to allow streaming infinite streams of data in this route.
   * By default the uploaded data is limited by the `akka.http.parsing.max-content-length`.
   */
  final def asSourceOfAsync[T](parallelism: Int)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    asSourceOfInternal[T](framing, (ec, mat) ⇒ Flow[ByteString].mapAsync(parallelism)(Unmarshal(_).to[T](um, ec, mat)))

  /**
   * Similar to [[asSourceOf]] however will apply at most `parallelism` unmarshallers in parallel.
   *
   * The source elements emitted preserve the order in which they are sent in the incoming [[HttpRequest]].
   * If you want to sacrivice ordering in favour of (potential) slight performance improvements in reading the input
   * you may want to use [[asSourceOfAsyncUnordered]] instead, which lifts the ordering guarantee.
   *
   * Refer to [[asSourceOf]] for more in depth-documentation and guidelines.
   *
   * See also [[MiscDirectives.withoutSizeLimit]] as you may want to allow streaming infinite streams of data in this route.
   * By default the uploaded data is limited by the `akka.http.parsing.max-content-length`.
   */
  final def asSourceOfAsync[T](parallelism: Int, framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): RequestToSourceUnmarshaller[T] =
    asSourceOfAsync(parallelism)(um, framing)

  /**
   * Similar to [[asSourceOfAsync]], as it will apply at most `parallelism` unmarshallers in parallel.
   *
   * The source elements emitted preserve the order in which they are sent in the incoming [[HttpRequest]].
   * If you want to sacrivice ordering in favour of (potential) slight performance improvements in reading the input
   * you may want to use [[asSourceOfAsyncUnordered]] instead, which lifts the ordering guarantee.
   *
   * Refer to [[asSourceOf]] for more in depth-documentation and guidelines.
   *
   * See also [[MiscDirectives.withoutSizeLimit]] as you may want to allow streaming infinite streams of data in this route.
   * By default the uploaded data is limited by the `akka.http.parsing.max-content-length`.
   */
  final def asSourceOfAsyncUnordered[T](parallelism: Int)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): RequestToSourceUnmarshaller[T] =
    asSourceOfInternal[T](framing, (ec, mat) ⇒ Flow[ByteString].mapAsyncUnordered(parallelism)(Unmarshal(_).to[T](um, ec, mat)))
  /**
   * Similar to [[asSourceOfAsync]], as it will apply at most `parallelism` unmarshallers in parallel.
   *
   * The source elements emitted preserve the order in which they are sent in the incoming [[HttpRequest]].
   * If you want to sacrivice ordering in favour of (potential) slight performance improvements in reading the input
   * you may want to use [[asSourceOfAsyncUnordered]] instead, which lifts the ordering guarantee.
   *
   * Refer to [[asSourceOf]] for more in depth-documentation and guidelines.
   *
   * See also [[MiscDirectives.withoutSizeLimit]] as you may want to allow streaming infinite streams of data in this route.
   * By default the uploaded data is limited by the `akka.http.parsing.max-content-length`.
   */
  final def asSourceOfAsyncUnordered[T](parallelism: Int, framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): RequestToSourceUnmarshaller[T] =
    asSourceOfAsyncUnordered(parallelism)(um, framing)

  // format: OFF
  private final def asSourceOfInternal[T](framing: FramingWithContentType, marshalling: (ExecutionContext, Materializer) => Flow[ByteString, ByteString, NotUsed]#ReprMat[T, NotUsed]): RequestToSourceUnmarshaller[T] =
    Unmarshaller.withMaterializer[HttpRequest, Source[T, NotUsed]] { implicit ec ⇒ implicit mat ⇒ req ⇒
      val entity = req.entity
      if (framing.matches(entity.contentType)) {
        val bytes = entity.dataBytes
        val frames = bytes.viaMat(framing.flow)(Keep.right)
        val elements = frames.viaMat(marshalling(ec, mat))(Keep.right)
        FastFuture.successful(elements)

      } else FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(framing.supported))
    }
  // format: ON

  // TODO note to self - we need the same of ease of streaming stuff for the client side - i.e. the twitter firehose case.

  implicit def _asSourceUnmarshaller[T](implicit fem: FromEntityUnmarshaller[T], framing: FramingWithContentType): FromRequestUnmarshaller[Source[T, NotUsed]] = {
    Unmarshaller.withMaterializer[HttpRequest, Source[T, NotUsed]] { implicit ec ⇒ implicit mat ⇒ req ⇒
      val entity = req.entity
      if (framing.matches(entity.contentType)) {
        val bytes = entity.dataBytes
        val frames = bytes.viaMat(framing.flow)(Keep.right)
        val elements = frames.viaMat(Flow[ByteString].map(HttpEntity(entity.contentType, _)).mapAsync(1)(Unmarshal(_).to[T](fem, ec, mat)))(Keep.right)
        FastFuture.successful(elements)
      } else FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(framing.supported))
    }
  }

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
/**
 * Allows the [[MarshallingDirectives.entity]] directive to extract a [[Source]] of elements.
 *
 * See [[FramedEntityStreamingDirectives]] for detailed documentation.
 */
object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives {
  sealed class AsyncSourceRenderingMode
  final class AsyncRenderingOf[T](val source: Source[T, Any], val parallelism: Int) extends AsyncSourceRenderingMode
  final class AsyncUnorderedRenderingOf[T](val source: Source[T, Any], val parallelism: Int) extends AsyncSourceRenderingMode

}

/** Provides DSL for special rendering modes, e.g. `complete(source.renderAsync)` */
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
