/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import java.io.File

import akka.event.{ NoLogging, LoggingAdapter }

import scala.collection.immutable.VectorBuilder
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, ExecutionContext }
import scala.collection.immutable
import scala.util.{ Failure, Success, Try }
import akka.stream.Materializer
import akka.stream.io.SynchronousFileSource
import akka.stream.scaladsl.{ FlattenStrategy, Source }
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model.headers._
import akka.http.impl.engine.rendering.BodyPartRenderer
import FastFuture._

sealed trait Multipart {
  def mediaType: MultipartMediaType
  def parts: Source[Multipart.BodyPart, Any]

  /**
   * Converts this content into its strict counterpart.
   * The given ``timeout`` denotes the max time that an individual part must be read in.
   * The Future is failed with an TimeoutException if one part isn't read completely after the given timeout.
   */
  def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[Multipart.Strict]

  /**
   * Creates an entity from this multipart object.
   */
  def toEntity(charset: HttpCharset = HttpCharsets.`UTF-8`,
               boundary: String = BodyPartRenderer.randomBoundary())(implicit log: LoggingAdapter = NoLogging): MessageEntity = {
    val chunks =
      parts
        .transform(() ⇒ BodyPartRenderer.streamed(boundary, charset.nioCharset, partHeadersSizeHint = 128, log))
        .flatten(FlattenStrategy.concat)
    HttpEntity.Chunked(mediaType withBoundary boundary, chunks)
  }
}

object Multipart {

  trait Strict extends Multipart {
    def strictParts: immutable.Seq[BodyPart.Strict]

    override def toEntity(charset: HttpCharset, boundary: String)(implicit log: LoggingAdapter = NoLogging): HttpEntity.Strict = {
      val data = BodyPartRenderer.strict(strictParts, boundary, charset.nioCharset, partHeadersSizeHint = 128, log)
      HttpEntity(mediaType withBoundary boundary, data)
    }
  }

  trait BodyPart {
    def entity: BodyPartEntity
    def headers: immutable.Seq[HttpHeader]

    def contentDispositionHeader: Option[`Content-Disposition`] =
      headers.collectFirst { case x: `Content-Disposition` ⇒ x }
    def dispositionParams: Map[String, String] =
      contentDispositionHeader match {
        case Some(`Content-Disposition`(_, params)) ⇒ params
        case None                                   ⇒ Map.empty
      }
    def dispositionType: Option[ContentDispositionType] =
      contentDispositionHeader.map(_.dispositionType)

    def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[BodyPart.Strict]
  }

  object BodyPart {
    trait Strict extends BodyPart {
      override def entity: HttpEntity.Strict
    }
  }

  private def strictify[BP <: Multipart.BodyPart, BPS <: Multipart.BodyPart.Strict](parts: Source[BP, Any])(f: BP ⇒ Future[BPS])(implicit ec: ExecutionContext, fm: Materializer): Future[Vector[BPS]] =
    // TODO: move to Vector `:+` when https://issues.scala-lang.org/browse/SI-8930 is fixed
    parts.runFold(new VectorBuilder[Future[BPS]]) {
      case (builder, part) ⇒ builder += f(part)
    }.fast.flatMap(builder ⇒ FastFuture.sequence(builder.result()))

  //////////////////////// CONCRETE multipart types /////////////////////////

  /**
   * Basic model for multipart content as defined by http://tools.ietf.org/html/rfc2046.
   */
  sealed abstract class General extends Multipart {
    def mediaType: MultipartMediaType
    def parts: Source[General.BodyPart, Any]
    def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[General.Strict] =
      strictify(parts)(_.toStrict(timeout)).fast.map(General.Strict(mediaType, _))
  }
  object General {
    def apply(mediaType: MultipartMediaType, parts: BodyPart.Strict*): Strict = Strict(mediaType, parts.toVector)

    def apply(_mediaType: MultipartMediaType, _parts: Source[BodyPart, Any]): General =
      new General {
        def mediaType = _mediaType
        def parts = _parts
        override def toString = s"General($mediaType, $parts)"
      }

    def unapply(value: General): Option[(MultipartMediaType, Source[BodyPart, Any])] = Some(value.mediaType -> value.parts)

    /**
     * Strict [[General]].
     */
    case class Strict(mediaType: MultipartMediaType, strictParts: immutable.Seq[BodyPart.Strict]) extends General with Multipart.Strict {
      def parts: Source[BodyPart.Strict, Any] = Source(strictParts)
      override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer) =
        FastFuture.successful(this)
      override def productPrefix = "General.Strict"
    }

    /**
     * Body part of the [[General]] model.
     */
    sealed abstract class BodyPart extends Multipart.BodyPart {
      def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[BodyPart.Strict] =
        entity.toStrict(timeout).map(BodyPart.Strict(_, headers))
      def toFormDataBodyPart: Try[FormData.BodyPart]
      def toByteRangesBodyPart: Try[ByteRanges.BodyPart]

      private[BodyPart] def tryCreateFormDataBodyPart[T](f: (String, Map[String, String], immutable.Seq[HttpHeader]) ⇒ T): Try[T] = {
        val params = dispositionParams
        params.get("name") match {
          case Some(name) ⇒ Success(f(name, params - "name", headers.filterNot(_ is "content-disposition")))
          case None       ⇒ Failure(IllegalHeaderException("multipart/form-data part must contain `Content-Disposition` header with `name` parameter"))
        }
      }
      private[BodyPart] def tryCreateByteRangesBodyPart[T](f: (ContentRange, RangeUnit, immutable.Seq[HttpHeader]) ⇒ T): Try[T] =
        headers.collectFirst { case x: `Content-Range` ⇒ x } match {
          case Some(`Content-Range`(unit, range)) ⇒ Success(f(range, unit, headers.filterNot(_ is "content-range")))
          case None                               ⇒ Failure(IllegalHeaderException("multipart/byteranges part must contain `Content-Range` header"))
        }
    }
    object BodyPart {
      def apply(_entity: BodyPartEntity, _headers: immutable.Seq[HttpHeader] = Nil): BodyPart =
        new BodyPart {
          def entity = _entity
          def headers: immutable.Seq[HttpHeader] = _headers
          def toFormDataBodyPart: Try[FormData.BodyPart] = tryCreateFormDataBodyPart(FormData.BodyPart(_, entity, _, _))
          def toByteRangesBodyPart: Try[ByteRanges.BodyPart] = tryCreateByteRangesBodyPart(ByteRanges.BodyPart(_, entity, _, _))
          override def toString = s"General.BodyPart($entity, $headers)"
        }

      def unapply(value: BodyPart): Option[(BodyPartEntity, immutable.Seq[HttpHeader])] = Some(value.entity -> value.headers)

      /**
       * Strict [[General.BodyPart]].
       */
      case class Strict(entity: HttpEntity.Strict, headers: immutable.Seq[HttpHeader] = Nil) extends BodyPart with Multipart.BodyPart.Strict {
        override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[Strict] =
          FastFuture.successful(this)
        override def toFormDataBodyPart: Try[FormData.BodyPart.Strict] = tryCreateFormDataBodyPart(FormData.BodyPart.Strict(_, entity, _, _))
        override def toByteRangesBodyPart: Try[ByteRanges.BodyPart.Strict] = tryCreateByteRangesBodyPart(ByteRanges.BodyPart.Strict(_, entity, _, _))
        override def productPrefix = "General.BodyPart.Strict"
      }
    }
  }

  /**
   * Model for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
   * All parts must have distinct names. (This is not verified!)
   */
  sealed abstract class FormData extends Multipart {
    def mediaType = MediaTypes.`multipart/form-data`
    def parts: Source[FormData.BodyPart, Any]
    def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[FormData.Strict] =
      strictify(parts)(_.toStrict(timeout)).fast.map(FormData.Strict(_))
  }
  object FormData {
    def apply(parts: BodyPart.Strict*): Strict = Strict(parts.toVector)
    def apply(parts: BodyPart*): FormData = FormData(Source(parts.toVector))

    def apply(fields: Map[String, HttpEntity.Strict]): Strict = Strict {
      fields.map { case (name, entity) ⇒ BodyPart.Strict(name, entity) }(collection.breakOut)
    }

    def apply(_parts: Source[BodyPart, Any]): FormData = new FormData {
      def parts = _parts
      override def toString = s"FormData($parts)"
    }

    /**
     * Creates a FormData instance that contains a single part backed by the given file.
     *
     * To create an instance with several parts or for multiple files, use
     * ``FormData(BodyPart.fromFile("field1", ...), BodyPart.fromFile("field2", ...)``
     */
    def fromFile(name: String, contentType: ContentType, file: File, chunkSize: Int = -1): FormData =
      FormData(Source.single(BodyPart.fromFile(name, contentType, file, chunkSize)))

    /**
     * Strict [[FormData]].
     */
    case class Strict(strictParts: immutable.Seq[BodyPart.Strict]) extends FormData with Multipart.Strict {
      def parts: Source[BodyPart.Strict, Any] = Source(strictParts)
      override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer) =
        FastFuture.successful(this)
      override def productPrefix = "FormData.Strict"
    }

    /**
     * Body part of the [[FormData]] model.
     */
    sealed abstract class BodyPart extends Multipart.BodyPart {
      def name: String
      def additionalDispositionParams: Map[String, String]
      def additionalHeaders: immutable.Seq[HttpHeader]
      override def headers = contentDispositionHeader.get +: additionalHeaders
      override def contentDispositionHeader = Some(`Content-Disposition`(dispositionType.get, dispositionParams))
      override def dispositionParams = additionalDispositionParams.updated("name", name)
      override def dispositionType = Some(ContentDispositionTypes.`form-data`)
      def filename: Option[String] = additionalDispositionParams.get("filename")
      def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[BodyPart.Strict] =
        entity.toStrict(timeout).map(BodyPart.Strict(name, _, additionalDispositionParams, additionalHeaders))
    }
    object BodyPart {
      def apply(_name: String, _entity: BodyPartEntity,
                _additionalDispositionParams: Map[String, String] = Map.empty,
                _additionalHeaders: immutable.Seq[HttpHeader] = Nil): BodyPart =
        new BodyPart {
          def name = _name
          def additionalDispositionParams = _additionalDispositionParams
          def additionalHeaders = _additionalHeaders
          def entity = _entity
          override def toString = s"FormData.BodyPart($name, $entity, $additionalDispositionParams, $additionalHeaders)"
        }

      /**
       * Creates a BodyPart backed by a File that will be streamed using a SynchronousFileSource.
       */
      def fromFile(name: String, contentType: ContentType, file: File, chunkSize: Int = -1): BodyPart =
        BodyPart(name, HttpEntity(contentType, file, chunkSize), Map("filename" -> file.getName))

      def unapply(value: BodyPart): Option[(String, BodyPartEntity, Map[String, String], immutable.Seq[HttpHeader])] =
        Some((value.name, value.entity, value.additionalDispositionParams, value.additionalHeaders))

      /**
       * Strict [[FormData.BodyPart]].
       */
      case class Strict(name: String, entity: HttpEntity.Strict,
                        additionalDispositionParams: Map[String, String] = Map.empty,
                        additionalHeaders: immutable.Seq[HttpHeader] = Nil) extends BodyPart with Multipart.BodyPart.Strict {
        override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[Strict] =
          FastFuture.successful(this)
        override def productPrefix = "FormData.BodyPart.Strict"
      }
    }
  }

  /**
   * Model for ``multipart/byteranges`` content as defined by
   * https://tools.ietf.org/html/rfc7233#section-5.4.1 and https://tools.ietf.org/html/rfc7233#appendix-A
   */
  sealed abstract class ByteRanges extends Multipart {
    def mediaType = MediaTypes.`multipart/byteranges`
    def parts: Source[ByteRanges.BodyPart, Any]
    def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[ByteRanges.Strict] =
      strictify(parts)(_.toStrict(timeout)).fast.map(ByteRanges.Strict(_))
  }
  object ByteRanges {
    def apply(parts: BodyPart.Strict*): Strict = Strict(parts.toVector)

    def apply(_parts: Source[BodyPart, Any]): ByteRanges =
      new ByteRanges {
        def parts = _parts
        override def toString = s"ByteRanges($parts)"
      }

    /**
     * Strict [[ByteRanges]].
     */
    case class Strict(strictParts: immutable.Seq[BodyPart.Strict]) extends ByteRanges with Multipart.Strict {
      def parts: Source[BodyPart.Strict, Any] = Source(strictParts)
      override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer) =
        FastFuture.successful(this)
      override def productPrefix = "ByteRanges.Strict"
    }

    /**
     * Body part of the [[ByteRanges]] model.
     */
    sealed abstract class BodyPart extends Multipart.BodyPart {
      def contentRange: ContentRange
      def rangeUnit: RangeUnit
      def additionalHeaders: immutable.Seq[HttpHeader]
      override def headers = contentRangeHeader +: additionalHeaders
      def contentRangeHeader = `Content-Range`(rangeUnit, contentRange)
      def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[BodyPart.Strict] =
        entity.toStrict(timeout).map(BodyPart.Strict(contentRange, _, rangeUnit, additionalHeaders))
    }
    object BodyPart {
      def apply(_contentRange: ContentRange, _entity: BodyPartEntity, _rangeUnit: RangeUnit = RangeUnits.Bytes,
                _additionalHeaders: immutable.Seq[HttpHeader] = Nil): BodyPart =
        new BodyPart {
          def contentRange = _contentRange
          def entity = _entity
          def rangeUnit = _rangeUnit
          def additionalHeaders = _additionalHeaders
          override def toString = s"ByteRanges.BodyPart($contentRange, $entity, $rangeUnit, $additionalHeaders)"
        }

      def unapply(value: BodyPart): Option[(ContentRange, BodyPartEntity, RangeUnit, immutable.Seq[HttpHeader])] =
        Some((value.contentRange, value.entity, value.rangeUnit, value.additionalHeaders))

      /**
       * Strict [[ByteRanges.BodyPart]].
       */
      case class Strict(contentRange: ContentRange, entity: HttpEntity.Strict, rangeUnit: RangeUnit = RangeUnits.Bytes,
                        additionalHeaders: immutable.Seq[HttpHeader] = Nil) extends BodyPart with Multipart.BodyPart.Strict {
        override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[Strict] =
          FastFuture.successful(this)
        override def productPrefix = "ByteRanges.BodyPart.Strict"
      }
    }
  }
}