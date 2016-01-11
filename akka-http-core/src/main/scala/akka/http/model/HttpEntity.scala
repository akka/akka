/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import language.implicitConversions
import java.io.File
import org.reactivestreams.Publisher
import scala.collection.immutable
import akka.util.ByteString

import akka.stream.{ TimerTransformer, FlowMaterializer }
import akka.stream.scaladsl.Flow
import akka.stream.impl.{ EmptyPublisher, SynchronousPublisherFromIterable }
import java.lang.Iterable
import japi.JavaMapping.Implicits._
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

/**
 * Models the entity (aka "body" or "content) of an HTTP message.
 */
sealed trait HttpEntity extends japi.HttpEntity {
  /**
   * Determines whether this entity is known to be empty.
   */
  def isKnownEmpty: Boolean

  /**
   * The `ContentType` associated with this entity.
   */
  def contentType: ContentType

  /**
   * A stream of the data of this entity.
   */
  def dataBytes(materializer: FlowMaterializer): Publisher[ByteString]

  /**
   * Collects all possible parts and returns a future Strict entity for easier processing. The future is failed with an
   * TimeoutException if the stream isn't completed after the given timeout.
   */
  def toStrict(timeout: FiniteDuration, materializer: FlowMaterializer)(implicit ec: ExecutionContext): Future[HttpEntity.Strict] =
    Flow(dataBytes(materializer))
      .timerTransform("toStrict", () ⇒ new TimerTransformer[ByteString, HttpEntity.Strict] {
        var bytes = ByteString.newBuilder
        scheduleOnce("", timeout)

        def onNext(element: ByteString): immutable.Seq[HttpEntity.Strict] = {
          bytes ++= element
          Nil
        }

        override def onTermination(e: Option[Throwable]): immutable.Seq[HttpEntity.Strict] =
          HttpEntity.Strict(contentType, bytes.result) :: Nil

        def onTimer(timerKey: Any): immutable.Seq[HttpEntity.Strict] =
          throw new java.util.concurrent.TimeoutException(
            s"HttpEntity.toStrict timed out after $timeout while still waiting for outstanding data")
      })
      .toFuture()(materializer)

  /**
   * Creates a copy of this HttpEntity with the `contentType` overridden with the given one.
   */
  def withContentType(contentType: ContentType): HttpEntity

  /** Java API */
  def getDataBytes(materializer: FlowMaterializer): Publisher[ByteString] = dataBytes(materializer)

  // default implementations, should be overridden
  def isCloseDelimited: Boolean = false
  def isDefault: Boolean = false
  def isChunked: Boolean = false
  def isRegular: Boolean = false
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
  def apply(contentType: ContentType, contentLength: Long, data: Publisher[ByteString]): Regular =
    if (contentLength == 0) empty(contentType) else Default(contentType, contentLength, data)

  def apply(contentType: ContentType, file: File): Regular = {
    val fileLength = file.length
    if (fileLength > 0) Default(contentType, fileLength, ???) // FIXME: attach from-file-Publisher
    else empty(contentType)
  }

  val Empty: Strict = Strict(ContentTypes.NoContentType, data = ByteString.empty)

  def empty(contentType: ContentType): Strict =
    if (contentType == Empty.contentType) Empty
    else Strict(contentType, data = ByteString.empty)

  /**
   * An HttpEntity that is "well-behaved" according to the HTTP/1.1 spec as that
   * it is either chunked or defines a content-length that is known a-priori.
   * Close-delimited entities are not `Regular` as they exists primarily for backwards compatibility with HTTP/1.0.
   */
  sealed trait Regular extends japi.HttpEntityRegular with HttpEntity {
    def withContentType(contentType: ContentType): HttpEntity.Regular
    override def isRegular: Boolean = true
  }

  // TODO: re-establish serializability
  // TODO: equal/hashcode ?

  /**
   * The model for the entity of a "regular" unchunked HTTP message with known, fixed data.
   * @param contentType
   * @param data
   */
  final case class Strict(contentType: ContentType, data: ByteString) extends japi.HttpEntityStrict with Regular {
    def isKnownEmpty: Boolean = data.isEmpty

    def dataBytes(materializer: FlowMaterializer): Publisher[ByteString] = SynchronousPublisherFromIterable(data :: Nil)

    override def toStrict(timeout: FiniteDuration, materializer: FlowMaterializer)(implicit ec: ExecutionContext): Future[Strict] =
      Future.successful(this)

    def withContentType(contentType: ContentType): Strict =
      if (contentType == this.contentType) this else copy(contentType = contentType)
  }

  /**
   * The model for the entity of a "regular" unchunked HTTP message with a known non-zero length.
   */
  final case class Default(contentType: ContentType,
                           contentLength: Long,
                           data: Publisher[ByteString]) extends japi.HttpEntityDefault with Regular {
    require(contentLength > 0, "contentLength must be positive (use `HttpEntity.empty(contentType)` for empty entities)")
    def isKnownEmpty = false
    override def isDefault: Boolean = true

    def dataBytes(materializer: FlowMaterializer): Publisher[ByteString] = data

    def withContentType(contentType: ContentType): Default =
      if (contentType == this.contentType) this else copy(contentType = contentType)
  }

  /**
   * The model for the entity of an HTTP response that is terminated by the server closing the connection.
   * The content-length of such responses is unknown at the time the response headers have been received.
   * Note that this type of HttpEntity cannot be used for HttpRequests!
   */
  final case class CloseDelimited(contentType: ContentType, data: Publisher[ByteString]) extends japi.HttpEntityCloseDelimited with HttpEntity {
    def isKnownEmpty = data eq EmptyPublisher
    override def isCloseDelimited: Boolean = true

    def dataBytes(materializer: FlowMaterializer): Publisher[ByteString] = data

    def withContentType(contentType: ContentType): CloseDelimited =
      if (contentType == this.contentType) this else copy(contentType = contentType)
  }

  /**
   * The model for the entity of a chunked HTTP message (with `Transfer-Encoding: chunked`).
   */
  final case class Chunked(contentType: ContentType, chunks: Publisher[ChunkStreamPart]) extends japi.HttpEntityChunked with Regular {
    def isKnownEmpty = chunks eq EmptyPublisher
    override def isChunked: Boolean = true

    def dataBytes(materializer: FlowMaterializer): Publisher[ByteString] =
      Flow(chunks).map(_.data).filter(_.nonEmpty).toPublisher()(materializer)

    def withContentType(contentType: ContentType): Chunked =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    /** Java API */
    def getChunks: Publisher[japi.ChunkStreamPart] = chunks.asInstanceOf[Publisher[japi.ChunkStreamPart]]
  }
  object Chunked {
    /**
     * Returns a ``Chunked`` entity where one Chunk is produced for every non-empty ByteString of the given
     * ``Publisher[ByteString]``.
     */
    def apply(contentType: ContentType, chunks: Publisher[ByteString], materializer: FlowMaterializer): Chunked =
      Chunked(contentType, Flow(chunks).collect[ChunkStreamPart] {
        case b: ByteString if b.nonEmpty ⇒ Chunk(b)
      }.toPublisher()(materializer))
  }

  /**
   * An element of the HttpEntity data stream.
   * Can be either a `Chunk` or a `LastChunk`.
   */
  sealed abstract class ChunkStreamPart extends japi.ChunkStreamPart {
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

    def getTrailerHeaders: Iterable[japi.HttpHeader] = java.util.Collections.emptyList[japi.HttpHeader]
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
    def getTrailerHeaders: Iterable[japi.HttpHeader] = trailer.asJava
  }
  object LastChunk extends LastChunk("", Nil)
}