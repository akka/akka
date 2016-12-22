/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model
import java.io.File
import java.nio.file.Path
import java.util.Optional

import akka.http.impl.util.{ DefaultNoLogging, Util }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }
import akka.event.LoggingAdapter
import akka.stream.impl.ConstantFun
import akka.stream.Materializer
import akka.stream.javadsl.{ Source ⇒ JSource }
import akka.stream.scaladsl._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model.headers._
import akka.http.impl.engine.rendering.BodyPartRenderer
import akka.http.javadsl.{ model ⇒ jm }
import FastFuture._
import akka.http.impl.util.JavaMapping.Implicits._

import scala.compat.java8.FutureConverters._
import java.util.concurrent.CompletionStage

/**
 * The model of multipart content for media-types `multipart/\*` (general multipart content),
 * `multipart/form-data` and `multipart/byteranges`.
 *
 * The basic modelling classes for these media-types ([[Multipart.General]], [[Multipart.FormData]] and
 * [[Multipart.ByteRanges]], respectively) are stream-based but each have a strict counterpart
 * (namely [[Multipart.General.Strict]], [[Multipart.FormData.Strict]] and [[Multipart.ByteRanges.Strict]]).
 */
sealed trait Multipart extends jm.Multipart {

  /**
   * The media-type this multipart content carries.
   */
  def mediaType: MediaType.Multipart

  /**
   * The stream of body parts this content consists of.
   */
  def parts: Source[Multipart.BodyPart, Any]

  /**
   * Converts this content into its strict counterpart.
   * The given `timeout` denotes the max time that an individual part must be read in.
   * The Future is failed with an TimeoutException if one part isn't read completely after the given timeout.
   */
  def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.Strict]

  /**
   * Creates a [[akka.http.scaladsl.model.MessageEntity]] from this multipart object.
   *
   * @deprecated This variant of `toEntity` is not supported any more. The charset parameter will be ignored. Please use
   *             one of the other overloads instead.
   */
  @deprecated(
    message = "This variant of `toEntity` is not supported any more. The charset parameter will be ignored. " +
    "Please use the variant without specifying the charset.",
    since = "10.0.0")
  def toEntity(
    charset:  HttpCharset = HttpCharsets.`UTF-8`,
    boundary: String      = BodyPartRenderer.randomBoundary())(implicit log: LoggingAdapter = DefaultNoLogging): MessageEntity =
    toEntity(boundary, log)

  /**
   * Creates an entity from this multipart object using the specified boundary and logger.
   */
  def toEntity(boundary: String, log: LoggingAdapter): MessageEntity = {
    val chunks =
      parts
        .via(BodyPartRenderer.streamed(boundary, partHeadersSizeHint = 128, log))
        .flatMapConcat(ConstantFun.scalaIdentityFunction)
    HttpEntity.Chunked(mediaType withBoundary boundary, chunks)
  }

  /**
   * Creates an entity from this multipart object using the specified boundary.
   */
  def toEntity(boundary: String): MessageEntity = toEntity(boundary, DefaultNoLogging)

  /**
   * Creates an entity from this multipart object using a random boundary.
   */
  def toEntity: MessageEntity = toEntity(BodyPartRenderer.randomBoundary(), DefaultNoLogging)

  /** Java API */
  def getMediaType: jm.MediaType.Multipart = mediaType

  /** Java API */
  def getParts: JSource[_ <: jm.Multipart.BodyPart, AnyRef] =
    JSource.fromGraph(parts.asInstanceOf[Source[Multipart.BodyPart, AnyRef]])

  /** Java API */
  def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[_ <: jm.Multipart.Strict] =
    toStrict(FiniteDuration(timeoutMillis, concurrent.duration.MILLISECONDS))(materializer).toJava

  /**
   * Java API
   *
   * @deprecated This variant of `toEntity` is not supported any more. The charset parameter will be ignored. Please use
   *             one of the other overloads instead.
   */
  @deprecated(
    message = "This variant of `toEntity` is not supported any more. The charset parameter will be ignored. " +
    "Please use the variant without specifying the charset.",
    since = "10.0.0")
  def toEntity(charset: jm.HttpCharset, boundary: String): jm.RequestEntity =
    toEntity(boundary)
}

object Multipart {

  /**
   * A type of multipart content for which all parts have already been loaded into memory
   * and are therefore allow random access.
   */
  trait Strict extends Multipart with jm.Multipart.Strict {

    def parts: Source[Multipart.BodyPart.Strict, Any]

    /**
     * The parts of this content as a strict collection.
     */
    def strictParts: immutable.Seq[Multipart.BodyPart.Strict]

    override def toEntity(charset: HttpCharset, boundary: String)(implicit log: LoggingAdapter = DefaultNoLogging): HttpEntity.Strict =
      toEntity(boundary, log)

    /**
     * Creates an entity from this multipart object using the specified boundary and logger.
     */
    override def toEntity(boundary: String, log: LoggingAdapter): HttpEntity.Strict = {
      val data = BodyPartRenderer.strict(strictParts, boundary, partHeadersSizeHint = 128, log)
      HttpEntity(mediaType withBoundary boundary, data)
    }

    /**
     * Creates an entity from this multipart object using the specified boundary.
     */
    override def toEntity(boundary: String): HttpEntity.Strict = toEntity(boundary, DefaultNoLogging)

    /**
     * Creates an entity from this multipart object using a random boundary.
     */
    override def toEntity: HttpEntity.Strict = toEntity(BodyPartRenderer.randomBoundary(), DefaultNoLogging)

    /** Java API */
    override def getParts: JSource[_ <: jm.Multipart.BodyPart.Strict, AnyRef] =
      super.getParts.asInstanceOf[JSource[_ <: jm.Multipart.BodyPart.Strict, AnyRef]]

    /** Java API */
    override def getStrictParts: java.lang.Iterable[_ <: jm.Multipart.BodyPart.Strict] =
      (strictParts: immutable.Seq[jm.Multipart.BodyPart.Strict]).asJava

    /** Java API */
    override def toEntity(charset: jm.HttpCharset, boundary: String): jm.HttpEntity.Strict =
      super.toEntity(boundary).asInstanceOf[jm.HttpEntity.Strict]
  }

  /**
   * The general model for a single part of a multipart message.
   */
  trait BodyPart extends jm.Multipart.BodyPart {

    /**
     * The entity of the part.
     */
    def entity: BodyPartEntity

    /**
     * The headers the part carries.
     */
    def headers: immutable.Seq[HttpHeader]

    /**
     * The potentially present [[`Content-Disposition`]] header.
     */
    def contentDispositionHeader: Option[`Content-Disposition`] =
      headers.collectFirst { case x: `Content-Disposition` ⇒ x }

    /**
     * The parameters of the potentially present [[`Content-Disposition`]] header.
     * Returns an empty map if no such header is present.
     */
    def dispositionParams: Map[String, String] =
      contentDispositionHeader match {
        case Some(`Content-Disposition`(_, params)) ⇒ params
        case None                                   ⇒ Map.empty
      }

    /**
     * The [[akka.http.scaladsl.model.headers.ContentDispositionType]] of the potentially present [[`Content-Disposition`]] header.
     */
    def dispositionType: Option[ContentDispositionType] =
      contentDispositionHeader.map(_.dispositionType)

    def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[BodyPart.Strict]

    /** Java API */
    def getEntity: jm.BodyPartEntity = entity

    /** Java API */
    def getHeaders: java.lang.Iterable[jm.HttpHeader] = (headers: immutable.Seq[jm.HttpHeader]).asJava

    /** Java API */
    def getContentDispositionHeader: Optional[jm.headers.ContentDisposition] = Util.convertOption(contentDispositionHeader)

    /** Java API */
    def getDispositionParams: java.util.Map[String, String] = dispositionParams.asJava

    /** Java API */
    def getDispositionType: Optional[jm.headers.ContentDispositionType] = Util.convertOption(dispositionType)

    /** Java API */
    def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[_ <: jm.Multipart.BodyPart.Strict] =
      toStrict(FiniteDuration(timeoutMillis, concurrent.duration.MILLISECONDS))(materializer).toJava
  }

  object BodyPart {

    /**
     * A [[BodyPart]] whose entity has already been loaded in its entirety and is therefore
     * full and readily available as a [[HttpEntity.Strict]].
     */
    trait Strict extends Multipart.BodyPart with jm.Multipart.BodyPart.Strict {
      override def entity: HttpEntity.Strict

      /** Java API */
      override def getEntity: jm.HttpEntity.Strict = entity
    }
  }

  private def strictify[BP <: Multipart.BodyPart, BPS <: Multipart.BodyPart.Strict](parts: Source[BP, Any])(f: BP ⇒ Future[BPS])(implicit fm: Materializer): Future[Vector[BPS]] = {
    import fm.executionContext
    parts.mapAsync(Int.MaxValue)(f).runWith(Sink.seq).fast.map(_.toVector)
  }

  //////////////////////// CONCRETE multipart types /////////////////////////

  /**
   * Basic model for general multipart content as defined by http://tools.ietf.org/html/rfc2046.
   */
  sealed abstract class General extends Multipart with jm.Multipart.General {
    def parts: Source[Multipart.General.BodyPart, Any]

    def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.General.Strict] = {
      import fm.executionContext
      strictify(parts)(_.toStrict(timeout)).fast.map(General.Strict(mediaType, _))
    }

    /** Java API */
    override def getParts: JSource[_ <: jm.Multipart.General.BodyPart, AnyRef] =
      super.getParts.asInstanceOf[JSource[_ <: jm.Multipart.General.BodyPart, AnyRef]]

    /** Java API */
    override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.Multipart.General.Strict] =
      super.toStrict(timeoutMillis, materializer).toScala.asInstanceOf[Future[jm.Multipart.General.Strict]].toJava
  }
  object General {
    def apply(mediaType: MediaType.Multipart, parts: BodyPart.Strict*): Strict = Strict(mediaType, parts.toVector)

    def apply(_mediaType: MediaType.Multipart, _parts: Source[Multipart.General.BodyPart, Any]): Multipart.General =
      new Multipart.General {
        def mediaType = _mediaType
        def parts = _parts
        override def toString = s"General($mediaType, $parts)"
      }

    def unapply(value: Multipart.General): Option[(MediaType.Multipart, Source[Multipart.General.BodyPart, Any])] =
      Some(value.mediaType → value.parts)

    /**
     * Strict [[General]] multipart content.
     */
    case class Strict(mediaType: MediaType.Multipart, strictParts: immutable.Seq[Multipart.General.BodyPart.Strict])
      extends Multipart.General with Multipart.Strict with jm.Multipart.General.Strict {
      def parts: Source[Multipart.General.BodyPart.Strict, Any] = Source(strictParts)
      override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer) = FastFuture.successful(this)
      override def productPrefix = "General.Strict"

      /** Java API */
      override def getParts: JSource[jm.Multipart.General.BodyPart.Strict, AnyRef] =
        super.getParts.asInstanceOf[JSource[_ <: jm.Multipart.General.BodyPart.Strict, AnyRef]]

      /** Java API */
      override def getStrictParts: java.lang.Iterable[jm.Multipart.General.BodyPart.Strict] =
        super.getStrictParts.asInstanceOf[java.lang.Iterable[jm.Multipart.General.BodyPart.Strict]]
    }

    /**
     * Body part of the [[General]] model.
     */
    sealed abstract class BodyPart extends Multipart.BodyPart with jm.Multipart.General.BodyPart {
      def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.General.BodyPart.Strict] = {
        import fm.executionContext
        entity.toStrict(timeout).map(BodyPart.Strict(_, headers))
      }
      def toFormDataBodyPart: Try[Multipart.FormData.BodyPart]
      def toByteRangesBodyPart: Try[Multipart.ByteRanges.BodyPart]

      /** Java API */
      override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.Multipart.General.BodyPart.Strict] =
        super.toStrict(timeoutMillis, materializer).toScala.asInstanceOf[Future[jm.Multipart.General.BodyPart.Strict]].toJava

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
      def apply(_entity: BodyPartEntity, _headers: immutable.Seq[HttpHeader] = Nil): Multipart.General.BodyPart =
        new Multipart.General.BodyPart {
          def entity = _entity
          def headers: immutable.Seq[HttpHeader] = _headers
          def toFormDataBodyPart: Try[Multipart.FormData.BodyPart] =
            tryCreateFormDataBodyPart(FormData.BodyPart(_, entity, _, _))
          def toByteRangesBodyPart: Try[Multipart.ByteRanges.BodyPart] =
            tryCreateByteRangesBodyPart(ByteRanges.BodyPart(_, entity, _, _))
          override def toString = s"General.BodyPart($entity, $headers)"
        }

      def unapply(value: BodyPart): Option[(BodyPartEntity, immutable.Seq[HttpHeader])] = Some(value.entity → value.headers)

      /**
       * Strict [[General.BodyPart]].
       */
      case class Strict(entity: HttpEntity.Strict, headers: immutable.Seq[HttpHeader] = Nil)
        extends BodyPart with Multipart.BodyPart.Strict with jm.Multipart.General.BodyPart.Strict {
        override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.General.BodyPart.Strict] =
          FastFuture.successful(this)
        override def toFormDataBodyPart: Try[Multipart.FormData.BodyPart.Strict] =
          tryCreateFormDataBodyPart(FormData.BodyPart.Strict(_, entity, _, _))
        override def toByteRangesBodyPart: Try[Multipart.ByteRanges.BodyPart.Strict] =
          tryCreateByteRangesBodyPart(ByteRanges.BodyPart.Strict(_, entity, _, _))
        override def productPrefix = "General.BodyPart.Strict"
      }
    }
  }

  /**
   * Model for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
   * All parts must have distinct names. (This is not verified!)
   */
  sealed abstract class FormData extends Multipart with jm.Multipart.FormData {
    def mediaType = MediaTypes.`multipart/form-data`

    def parts: Source[Multipart.FormData.BodyPart, Any]

    def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.FormData.Strict] = {
      import fm.executionContext
      strictify(parts)(_.toStrict(timeout)).fast.map(Multipart.FormData.Strict(_))
    }

    /** Java API */
    override def getParts: JSource[_ <: jm.Multipart.FormData.BodyPart, AnyRef] =
      super.getParts.asInstanceOf[JSource[_ <: jm.Multipart.FormData.BodyPart, AnyRef]]

    /** Java API */
    override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.Multipart.FormData.Strict] =
      super.toStrict(timeoutMillis, materializer).toScala.asInstanceOf[Future[jm.Multipart.FormData.Strict]].toJava
  }
  object FormData {
    def apply(parts: Multipart.FormData.BodyPart.Strict*): Multipart.FormData.Strict = Strict(parts.toVector)
    def apply(parts: Multipart.FormData.BodyPart*): Multipart.FormData = Multipart.FormData(Source(parts.toVector))

    // FIXME: SI-2991 workaround - two functions below. Remove when (hopefully) this issue is fixed
    /** INTERNAL API */
    private[akka] def createStrict(parts: Multipart.FormData.BodyPart.Strict*): Multipart.FormData.Strict = Strict(parts.toVector)
    /** INTERNAL API */
    private[akka] def createNonStrict(parts: Multipart.FormData.BodyPart*): Multipart.FormData = Multipart.FormData(Source(parts.toVector))
    /** INTERNAL API */
    private[akka] def createStrict(fields: Map[String, akka.http.javadsl.model.HttpEntity.Strict]): Multipart.FormData.Strict = Multipart.FormData.Strict {
      fields.map { case (name, entity: akka.http.scaladsl.model.HttpEntity.Strict) ⇒ Multipart.FormData.BodyPart.Strict(name, entity) }(collection.breakOut)
    }
    /** INTERNAL API */
    private[akka] def createSource(parts: Source[akka.http.javadsl.model.Multipart.FormData.BodyPart, Any]): Multipart.FormData = {
      apply(parts.asInstanceOf[Source[Multipart.FormData.BodyPart, Any]])
    }

    def apply(fields: Map[String, HttpEntity.Strict]): Multipart.FormData.Strict = Multipart.FormData.Strict {
      fields.map { case (name, entity) ⇒ Multipart.FormData.BodyPart.Strict(name, entity) }(collection.breakOut)
    }

    def apply(_parts: Source[Multipart.FormData.BodyPart, Any]): Multipart.FormData =
      new Multipart.FormData {
        def parts = _parts
        override def toString = s"FormData($parts)"
      }

    /**
     * Creates a FormData instance that contains a single part backed by the given file.
     *
     * To create an instance with several parts or for multiple files, use
     * `FormData(BodyPart.fromFile("field1", ...), BodyPart.fromFile("field2", ...)`
     */
    @deprecated("Use `fromPath` instead", "2.4.5")
    def fromFile(name: String, contentType: ContentType, file: File, chunkSize: Int = -1): Multipart.FormData =
      fromPath(name, contentType, file.toPath, chunkSize)

    /**
     * Creates a FormData instance that contains a single part backed by the given file.
     *
     * To create an instance with several parts or for multiple files, use
     * `FormData(BodyPart.fromPath("field1", ...), BodyPart.fromPath("field2", ...)`
     */
    def fromPath(name: String, contentType: ContentType, file: Path, chunkSize: Int = -1): Multipart.FormData =
      Multipart.FormData(Source.single(Multipart.FormData.BodyPart.fromPath(name, contentType, file, chunkSize)))

    /**
     * Strict [[FormData]].
     */
    case class Strict(strictParts: immutable.Seq[Multipart.FormData.BodyPart.Strict])
      extends FormData with Multipart.Strict with jm.Multipart.FormData.Strict {
      def parts: Source[Multipart.FormData.BodyPart.Strict, Any] = Source(strictParts)
      override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer) = FastFuture.successful(this)
      override def productPrefix = "FormData.Strict"

      /** Java API */
      override def getParts: JSource[jm.Multipart.FormData.BodyPart.Strict, AnyRef] =
        super.getParts.asInstanceOf[JSource[jm.Multipart.FormData.BodyPart.Strict, AnyRef]]

      /** Java API */
      override def getStrictParts: java.lang.Iterable[jm.Multipart.FormData.BodyPart.Strict] =
        super.getStrictParts.asInstanceOf[java.lang.Iterable[jm.Multipart.FormData.BodyPart.Strict]]
    }

    /**
     * Body part of the [[FormData]] model.
     */
    sealed abstract class BodyPart extends Multipart.BodyPart with jm.Multipart.FormData.BodyPart {

      /**
       * The name of this part.
       */
      def name: String

      /**
       * The Content-Disposition parameters, not including the `name` parameter.
       */
      def additionalDispositionParams: Map[String, String]

      /**
       * Part headers, not including the Content-Disposition header.
       */
      def additionalHeaders: immutable.Seq[HttpHeader]

      override def headers = contentDispositionHeader.get +: additionalHeaders
      override def contentDispositionHeader = Some(`Content-Disposition`(dispositionType.get, dispositionParams))
      override def dispositionParams = additionalDispositionParams.updated("name", name)
      override def dispositionType = Some(ContentDispositionTypes.`form-data`)

      /**
       * The value of the `filename` Content-Disposition parameter, if available.
       */
      def filename: Option[String] = additionalDispositionParams.get("filename")

      def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.FormData.BodyPart.Strict] = {
        import fm.executionContext
        entity.toStrict(timeout).map(Multipart.FormData.BodyPart.Strict(name, _, additionalDispositionParams, additionalHeaders))
      }

      /** Java API */
      def getName: String = name

      /** Java API */
      def getAdditionalDispositionParams: java.util.Map[String, String] = additionalDispositionParams.asJava

      /** Java API */
      def getAdditionalHeaders: java.lang.Iterable[jm.HttpHeader] =
        (additionalHeaders: immutable.Seq[jm.HttpHeader]).asJava

      /** Java API */
      def getFilename: Optional[String] = filename.asJava

      /** Java API */
      override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.Multipart.FormData.BodyPart.Strict] =
        super.toStrict(timeoutMillis, materializer).toScala.asInstanceOf[Future[jm.Multipart.FormData.BodyPart.Strict]].toJava
    }
    object BodyPart {
      def apply(_name: String, _entity: BodyPartEntity,
                _additionalDispositionParams: Map[String, String]       = Map.empty,
                _additionalHeaders:           immutable.Seq[HttpHeader] = Nil): Multipart.FormData.BodyPart =
        new Multipart.FormData.BodyPart {
          def name = _name
          def additionalDispositionParams = _additionalDispositionParams
          def additionalHeaders = _additionalHeaders
          def entity = _entity
          override def toString = s"FormData.BodyPart($name, $entity, $additionalDispositionParams, $additionalHeaders)"
        }

      /**
       * Creates a BodyPart backed by a File that will be streamed using a FileSource.
       */
      @deprecated("Use `fromPath` instead", since = "2.4.5")
      def fromFile(name: String, contentType: ContentType, file: File, chunkSize: Int = -1): BodyPart =
        fromPath(name, contentType, file.toPath, chunkSize)

      /**
       * Creates a BodyPart backed by a file that will be streamed using a FileSource.
       */
      def fromPath(name: String, contentType: ContentType, file: Path, chunkSize: Int = -1): BodyPart =
        BodyPart(name, HttpEntity.fromPath(contentType, file, chunkSize), Map("filename" → file.getFileName.toString))

      def unapply(value: BodyPart): Option[(String, BodyPartEntity, Map[String, String], immutable.Seq[HttpHeader])] =
        Some((value.name, value.entity, value.additionalDispositionParams, value.additionalHeaders))

      /**
       * Strict [[FormData.BodyPart]].
       */
      case class Strict(name: String, entity: HttpEntity.Strict,
                        additionalDispositionParams: Map[String, String]       = Map.empty,
                        additionalHeaders:           immutable.Seq[HttpHeader] = Nil)
        extends Multipart.FormData.BodyPart with Multipart.BodyPart.Strict with jm.Multipart.FormData.BodyPart.Strict {
        override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.FormData.BodyPart.Strict] =
          FastFuture.successful(this)
        override def productPrefix = "FormData.BodyPart.Strict"
      }

      /** INTERNAL API */
      private[akka] object Builder {
        def create(_name: String, _entity: BodyPartEntity,
                   _additionalDispositionParams: Map[String, String],
                   _additionalHeaders:           Iterable[akka.http.javadsl.model.HttpHeader]): Multipart.FormData.BodyPart = {
          val _headers = _additionalHeaders.to[immutable.Seq] map { case h: akka.http.scaladsl.model.HttpHeader ⇒ h }
          apply(_name, _entity, _additionalDispositionParams, _headers)
        }
      }

      /** INTERNAL API */
      private[akka] object StrictBuilder {
        def createStrict(_name: String, _entity: HttpEntity.Strict,
                         _additionalDispositionParams: Map[String, String],
                         _additionalHeaders:           Iterable[akka.http.javadsl.model.HttpHeader]): Multipart.FormData.BodyPart.Strict = {
          val _headers = _additionalHeaders.to[immutable.Seq] map { case h: akka.http.scaladsl.model.HttpHeader ⇒ h }
          Strict(_name, _entity, _additionalDispositionParams, _headers)
        }
      }
    }
  }

  /**
   * Model for `multipart/byteranges` content as defined by
   * https://tools.ietf.org/html/rfc7233#section-5.4.1 and https://tools.ietf.org/html/rfc7233#appendix-A
   */
  sealed abstract class ByteRanges extends Multipart with jm.Multipart.ByteRanges {
    def mediaType = MediaTypes.`multipart/byteranges`
    def parts: Source[Multipart.ByteRanges.BodyPart, Any]
    def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.ByteRanges.Strict] = {
      import fm.executionContext
      strictify(parts)(_.toStrict(timeout)).fast.map(ByteRanges.Strict(_))
    }

    /** Java API */
    override def getParts: JSource[_ <: jm.Multipart.ByteRanges.BodyPart, AnyRef] =
      super.getParts.asInstanceOf[JSource[_ <: jm.Multipart.ByteRanges.BodyPart, AnyRef]]

    /** Java API */
    override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.Multipart.ByteRanges.Strict] =
      super.toStrict(timeoutMillis, materializer).toScala.asInstanceOf[Future[jm.Multipart.ByteRanges.Strict]].toJava
  }
  object ByteRanges {
    def apply(parts: Multipart.ByteRanges.BodyPart.Strict*): Strict = Strict(parts.toVector)

    def apply(_parts: Source[Multipart.ByteRanges.BodyPart, Any]): Multipart.ByteRanges =
      new Multipart.ByteRanges {
        def parts = _parts
        override def toString = s"ByteRanges($parts)"
      }

    /**
     * Strict [[ByteRanges]].
     */
    case class Strict(strictParts: immutable.Seq[Multipart.ByteRanges.BodyPart.Strict])
      extends Multipart.ByteRanges with Multipart.Strict with jm.Multipart.ByteRanges.Strict {
      def parts: Source[Multipart.ByteRanges.BodyPart.Strict, Any] = Source(strictParts)
      override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer) = FastFuture.successful(this)
      override def productPrefix = "ByteRanges.Strict"

      /** Java API */
      override def getParts: JSource[jm.Multipart.ByteRanges.BodyPart.Strict, AnyRef] =
        super.getParts.asInstanceOf[JSource[jm.Multipart.ByteRanges.BodyPart.Strict, AnyRef]]

      /** Java API */
      override def getStrictParts: java.lang.Iterable[jm.Multipart.ByteRanges.BodyPart.Strict] =
        super.getStrictParts.asInstanceOf[java.lang.Iterable[jm.Multipart.ByteRanges.BodyPart.Strict]]
    }

    /**
     * Body part of the [[ByteRanges]] model.
     */
    sealed abstract class BodyPart extends Multipart.BodyPart with jm.Multipart.ByteRanges.BodyPart {

      /**
       * The [[ContentRange]] contained in this part.
       */
      def contentRange: ContentRange

      /**
       * The [[akka.http.scaladsl.model.headers.RangeUnit]] for the `contentRange`.
       */
      def rangeUnit: RangeUnit

      /**
       * Part headers, not including the Content-Range header.
       */
      def additionalHeaders: immutable.Seq[HttpHeader]

      /**
       * The `Content-Range` header of this part.
       */
      def contentRangeHeader = `Content-Range`(rangeUnit, contentRange)

      override def headers = contentRangeHeader +: additionalHeaders
      def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.ByteRanges.BodyPart.Strict] = {
        import fm.executionContext
        entity.toStrict(timeout).map(Multipart.ByteRanges.BodyPart.Strict(contentRange, _, rangeUnit, additionalHeaders))
      }

      /** Java API */
      def getContentRange: jm.ContentRange = contentRange

      /** Java API */
      def getRangeUnit: RangeUnit = rangeUnit

      /** Java API */
      def getAdditionalHeaders: java.lang.Iterable[jm.HttpHeader] =
        (additionalHeaders: immutable.Seq[jm.HttpHeader]).asJava

      /** Java API */
      def getContentRangeHeader: jm.headers.ContentRange = contentRangeHeader

      /** Java API */
      override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.Multipart.ByteRanges.BodyPart.Strict] =
        super.toStrict(timeoutMillis, materializer).toScala.asInstanceOf[Future[jm.Multipart.ByteRanges.BodyPart.Strict]].toJava
    }
    object BodyPart {
      def apply(_contentRange: ContentRange, _entity: BodyPartEntity, _rangeUnit: RangeUnit = RangeUnits.Bytes,
                _additionalHeaders: immutable.Seq[HttpHeader] = Nil): Multipart.ByteRanges.BodyPart =
        new Multipart.ByteRanges.BodyPart {
          def contentRange = _contentRange
          def entity = _entity
          def rangeUnit = _rangeUnit
          def additionalHeaders = _additionalHeaders
          override def toString = s"ByteRanges.BodyPart($contentRange, $entity, $rangeUnit, $additionalHeaders)"
        }

      def unapply(value: Multipart.ByteRanges.BodyPart): Option[(ContentRange, BodyPartEntity, RangeUnit, immutable.Seq[HttpHeader])] =
        Some((value.contentRange, value.entity, value.rangeUnit, value.additionalHeaders))

      /**
       * Strict [[ByteRanges.BodyPart]].
       */
      case class Strict(contentRange: ContentRange, entity: HttpEntity.Strict, rangeUnit: RangeUnit = RangeUnits.Bytes,
                        additionalHeaders: immutable.Seq[HttpHeader] = Nil)
        extends Multipart.ByteRanges.BodyPart with Multipart.BodyPart.Strict with jm.Multipart.ByteRanges.BodyPart.Strict {
        override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[Multipart.ByteRanges.BodyPart.Strict] =
          FastFuture.successful(this)
        override def productPrefix = "ByteRanges.BodyPart.Strict"
      }
    }
  }
}
