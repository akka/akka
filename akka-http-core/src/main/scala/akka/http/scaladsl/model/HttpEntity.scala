/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import akka.http.javadsl.model.HttpEntityStrict

import language.implicitConversions
import java.io.File
import java.lang.{ Iterable ⇒ JIterable, Long ⇒ JLong }
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.immutable
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.stream.io.SynchronousFileSource
import akka.{ japi, stream }
import akka.http.scaladsl.util.FastFuture
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

/**
 * Models the entity (aka "body" or "content) of an HTTP message.
 */
sealed trait HttpEntity extends jm.HttpEntity {
  /**
   * Determines whether this entity is known to be empty.
   */
  def isKnownEmpty: Boolean

  /**
   * The `ContentType` associated with this entity.
   */
  def contentType: ContentType

  /**
   * Some(content length) if a length is defined for this entity, None otherwise.
   * A length is only defined for Strict and Default entity types.
   *
   * In many cases it's dangerous to rely on the (non-)existence of a content-length.
   * HTTP intermediaries like (transparent) proxies are allowed to change the transfer-encoding
   * which can result in the entity being delivered as another type as expected.
   */
  def contentLengthOption: Option[Long]

  /**
   * A stream of the data of this entity.
   */
  def dataBytes: Source[ByteString, Any]

  /**
   * Collects all possible parts and returns a potentially future Strict entity for easier processing.
   * The Future is failed with an TimeoutException if the stream isn't completed after the given timeout.
   */
  def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[HttpEntity.Strict] =
    dataBytes
      .via(new akka.http.impl.util.ToStrict(timeout, contentType))
      .runWith(Sink.head)

  /**
   * Returns a copy of the given entity with the ByteString chunks of this entity transformed by the given transformer.
   * For a `Chunked` entity, the chunks will be transformed one by one keeping the chunk metadata (but may introduce an
   * extra chunk before the `LastChunk` if `transformer.onTermination` returns additional data).
   *
   * This method may only throw an exception if the `transformer` function throws an exception while creating the transformer.
   * Any other errors are reported through the new entity data stream.
   */
  def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): HttpEntity

  /**
   * Creates a copy of this HttpEntity with the `contentType` overridden with the given one.
   */
  def withContentType(contentType: ContentType): HttpEntity

  /** Java API */
  def getDataBytes: stream.javadsl.Source[ByteString, AnyRef] = stream.javadsl.Source.fromGraph(dataBytes.asInstanceOf[Source[ByteString, AnyRef]])

  /** Java API */
  def getContentLengthOption: japi.Option[JLong] =
    japi.Option.fromScalaOption(contentLengthOption.asInstanceOf[Option[JLong]]) // Scala autoboxing

  // default implementations, should be overridden
  def isCloseDelimited: Boolean = false
  def isIndefiniteLength: Boolean = false
  def isDefault: Boolean = false
  def isChunked: Boolean = false

  /** Java API */
  def toStrict(timeoutMillis: Long, materializer: Materializer): Future[HttpEntityStrict] =
    toStrict(timeoutMillis.millis)(materializer)
}

/* An entity that can be used for body parts */
sealed trait BodyPartEntity extends HttpEntity with jm.BodyPartEntity {
  def withContentType(contentType: ContentType): BodyPartEntity
}

/**
 * An [[HttpEntity]] that can be used for requests.
 * Note that all entities that can be used for requests can also be used for responses.
 * (But not the other way around, since [[HttpEntity.CloseDelimited]] can only be used for responses!)
 */
sealed trait RequestEntity extends HttpEntity with jm.RequestEntity with ResponseEntity {
  def withContentType(contentType: ContentType): RequestEntity

  override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): RequestEntity
}

/**
 * An [[HttpEntity]] that can be used for responses.
 * Note that all entities that can be used for requests can also be used for responses.
 * (But not the other way around, since [[HttpEntity.CloseDelimited]] can only be used for responses!)
 */
sealed trait ResponseEntity extends HttpEntity with jm.ResponseEntity {
  def withContentType(contentType: ContentType): ResponseEntity

  override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): ResponseEntity
}
/* An entity that can be used for requests, responses, and body parts */
sealed trait UniversalEntity extends jm.UniversalEntity with MessageEntity with BodyPartEntity {
  def withContentType(contentType: ContentType): UniversalEntity
  def contentLength: Long
  def contentLengthOption: Option[Long] = Some(contentLength)

  /**
   * Transforms this' entities data bytes with a transformer that will produce exactly the number of bytes given as
   * ``newContentLength``.
   */
  def transformDataBytes(newContentLength: Long, transformer: Flow[ByteString, ByteString, Any]): UniversalEntity
}

object HttpEntity {
  implicit def apply(string: String): Strict = apply(ContentTypes.`text/plain(UTF-8)`, string)
  implicit def apply(bytes: Array[Byte]): Strict = apply(ContentTypes.`application/octet-stream`, bytes)
  implicit def apply(data: ByteString): Strict = apply(ContentTypes.`application/octet-stream`, data)
  def apply(contentType: ContentType, string: String): Strict =
    if (string.isEmpty) empty(contentType) else apply(contentType, ByteString(string.getBytes(contentType.charset.nioCharset)))
  def apply(contentType: ContentType, bytes: Array[Byte]): Strict =
    if (bytes.length == 0) empty(contentType) else apply(contentType, ByteString(bytes))
  def apply(contentType: ContentType, data: ByteString): Strict =
    if (data.isEmpty) empty(contentType) else Strict(contentType, data)

  def apply(contentType: ContentType, contentLength: Long, data: Source[ByteString, Any]): UniversalEntity =
    if (contentLength == 0) empty(contentType) else Default(contentType, contentLength, data)
  def apply(contentType: ContentType, data: Source[ByteString, Any]): Chunked =
    Chunked.fromData(contentType, data)

  def apply(contentType: ContentType, file: File, chunkSize: Int = -1): UniversalEntity = {
    val fileLength = file.length
    if (fileLength > 0)
      Default(contentType, fileLength,
        if (chunkSize > 0) SynchronousFileSource(file, chunkSize) else SynchronousFileSource(file))
    else empty(contentType)
  }

  val Empty: Strict = Strict(ContentTypes.NoContentType, data = ByteString.empty)

  def empty(contentType: ContentType): Strict =
    if (contentType == Empty.contentType) Empty
    else Strict(contentType, data = ByteString.empty)

  // TODO: re-establish serializability
  // TODO: equal/hashcode ?

  /**
   * The model for the entity of a "regular" unchunked HTTP message with known, fixed data.
   */
  final case class Strict(contentType: ContentType, data: ByteString)
    extends jm.HttpEntityStrict with UniversalEntity {

    def contentLength: Long = data.length

    def isKnownEmpty: Boolean = data.isEmpty

    def dataBytes: Source[ByteString, Unit] = Source(data :: Nil)

    override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer) =
      FastFuture.successful(this)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): MessageEntity =
      Chunked.fromData(contentType, Source.single(data).via(transformer))

    override def transformDataBytes(newContentLength: Long, transformer: Flow[ByteString, ByteString, Any]): UniversalEntity =
      Default(contentType, newContentLength, Source.single(data) via transformer)

    def withContentType(contentType: ContentType): Strict =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def productPrefix = "HttpEntity.Strict"
  }

  /**
   * The model for the entity of a "regular" unchunked HTTP message with a known non-zero length.
   */
  final case class Default(contentType: ContentType,
                           contentLength: Long,
                           data: Source[ByteString, Any])
    extends jm.HttpEntityDefault with UniversalEntity {
    require(contentLength > 0, "contentLength must be positive (use `HttpEntity.empty(contentType)` for empty entities)")
    def isKnownEmpty = false
    override def isDefault: Boolean = true

    def dataBytes: Source[ByteString, Any] = data

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): Chunked =
      Chunked.fromData(contentType, data via transformer)

    override def transformDataBytes(newContentLength: Long, transformer: Flow[ByteString, ByteString, Any]): UniversalEntity =
      Default(contentType, newContentLength, data via transformer)

    def withContentType(contentType: ContentType): Default =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def productPrefix = "HttpEntity.Default"
  }

  /**
   * Supertype of CloseDelimited and IndefiniteLength.
   *
   * INTERNAL API
   */
  private[http] sealed trait WithoutKnownLength extends HttpEntity {
    def contentType: ContentType
    def data: Source[ByteString, Any]
    def contentLengthOption: Option[Long] = None

    def isKnownEmpty = data eq Source.empty

    def dataBytes: Source[ByteString, Any] = data
  }

  /**
   * The model for the entity of an HTTP response that is terminated by the server closing the connection.
   * The content-length of such responses is unknown at the time the response headers have been received.
   * Note that this type of HttpEntity can only be used for HttpResponses.
   */
  final case class CloseDelimited(contentType: ContentType, data: Source[ByteString, Any])
    extends jm.HttpEntityCloseDelimited with ResponseEntity with WithoutKnownLength {
    type Self = CloseDelimited

    override def isCloseDelimited: Boolean = true
    def withContentType(contentType: ContentType): CloseDelimited =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): CloseDelimited =
      HttpEntity.CloseDelimited(contentType, data via transformer)

    override def productPrefix = "HttpEntity.CloseDelimited"
  }

  /**
   * The model for the entity of a BodyPart with an indefinite length.
   * Note that this type of HttpEntity can only be used for BodyParts.
   */
  final case class IndefiniteLength(contentType: ContentType, data: Source[ByteString, Any])
    extends jm.HttpEntityIndefiniteLength with BodyPartEntity with WithoutKnownLength {

    override def isIndefiniteLength: Boolean = true
    def withContentType(contentType: ContentType): IndefiniteLength =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): IndefiniteLength =
      HttpEntity.IndefiniteLength(contentType, data via transformer)

    override def productPrefix = "HttpEntity.IndefiniteLength"
  }

  /**
   * The model for the entity of a chunked HTTP message (with `Transfer-Encoding: chunked`).
   */
  final case class Chunked(contentType: ContentType, chunks: Source[ChunkStreamPart, Any])
    extends jm.HttpEntityChunked with MessageEntity {

    def isKnownEmpty = chunks eq Source.empty
    def contentLengthOption: Option[Long] = None

    override def isChunked: Boolean = true

    def dataBytes: Source[ByteString, Any] = chunks.map(_.data).filter(_.nonEmpty)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): Chunked = {
      val newData =
        chunks.map {
          case Chunk(data, "")    ⇒ data
          case LastChunk("", Nil) ⇒ ByteString.empty
          case _ ⇒
            throw new IllegalArgumentException("Chunked.transformDataBytes not allowed for chunks with metadata")
        } via transformer

      Chunked.fromData(contentType, newData)
    }

    def withContentType(contentType: ContentType): Chunked =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def productPrefix = "HttpEntity.Chunked"

    /** Java API */
    def getChunks: stream.javadsl.Source[jm.ChunkStreamPart, Any] = stream.javadsl.Source.fromGraph(chunks)
  }
  object Chunked {
    /**
     * Returns a ``Chunked`` entity where one Chunk is produced for every non-empty ByteString produced by the given
     * ``Source``.
     */
    def fromData(contentType: ContentType, chunks: Source[ByteString, Any]): Chunked =
      Chunked(contentType, chunks.collect[ChunkStreamPart] {
        case b: ByteString if b.nonEmpty ⇒ Chunk(b)
      })
  }

  /**
   * An element of the HttpEntity data stream.
   * Can be either a `Chunk` or a `LastChunk`.
   */
  sealed abstract class ChunkStreamPart extends jm.ChunkStreamPart {
    def data: ByteString
    def extension: String
    def isLastChunk: Boolean
  }
  object ChunkStreamPart {
    implicit def apply(string: String): ChunkStreamPart = Chunk(string)
    implicit def apply(bytes: Array[Byte]): ChunkStreamPart = Chunk(bytes)
    implicit def apply(bytes: ByteString): ChunkStreamPart = Chunk(bytes)
  }

  /**
   * An intermediate entity chunk guaranteed to carry non-empty data.
   */
  final case class Chunk(data: ByteString, extension: String = "") extends ChunkStreamPart {
    require(data.nonEmpty, "An HttpEntity.Chunk must have non-empty data")
    def isLastChunk = false

    /** Java API */
    def getTrailerHeaders: JIterable[jm.HttpHeader] = java.util.Collections.emptyList[jm.HttpHeader]
  }
  object Chunk {
    def apply(string: String): Chunk = apply(ByteString(string))
    def apply(bytes: Array[Byte]): Chunk = apply(ByteString(bytes))
  }

  /**
   * The final chunk of a chunk stream.
   * If you don't need extensions or trailer headers you can save an allocation
   * by directly using the `LastChunk` companion object.
   */
  case class LastChunk(extension: String = "", trailer: immutable.Seq[HttpHeader] = Nil) extends ChunkStreamPart {
    def data = ByteString.empty
    def isLastChunk = true

    /** Java API */
    def getTrailerHeaders: JIterable[jm.HttpHeader] = trailer.asJava
  }
  object LastChunk extends LastChunk("", Nil)
}
