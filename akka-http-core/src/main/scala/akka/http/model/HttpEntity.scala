/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import language.implicitConversions
import java.io.File
import java.lang.{ Iterable ⇒ JIterable }
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable
import akka.util.ByteString
import akka.stream.{ TimerTransformer, Transformer }
import akka.stream.scaladsl2._
import akka.stream.impl.{ EmptyPublisher, SynchronousPublisherFromIterable }
import akka.http.util._
import japi.JavaMapping.Implicits._

import scala.util.control.NonFatal

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
  def dataBytes: Source[ByteString]

  /**
   * Collects all possible parts and returns a potentially future Strict entity for easier processing.
   * The Deferrable is failed with an TimeoutException if the stream isn't completed after the given timeout.
   */
  def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: FlowMaterializer): Future[HttpEntity.Strict] = {
    def transformer() =
      new TimerTransformer[ByteString, HttpEntity.Strict] {
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
      }
    dataBytes.timerTransform("toStrict", transformer).runWith(Sink.future)
  }

  /**
   * Returns a copy of the given entity with the ByteString chunks of this entity transformed by the given transformer.
   * For a `Chunked` entity, the chunks will be transformed one by one keeping the chunk metadata (but may introduce an
   * extra chunk before the `LastChunk` if `transformer.onTermination` returns additional data).
   *
   * This method may only throw an exception if the `transformer` function throws an exception while creating the transformer.
   * Any other errors are reported through the new entity data stream.
   */
  def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): HttpEntity

  /**
   * Creates a copy of this HttpEntity with the `contentType` overridden with the given one.
   */
  def withContentType(contentType: ContentType): HttpEntity

  /** Java API */
  def getDataBytes: Source[ByteString] = dataBytes

  // default implementations, should be overridden
  def isCloseDelimited: Boolean = false
  def isIndefiniteLength: Boolean = false
  def isDefault: Boolean = false
  def isChunked: Boolean = false
}

/* An entity that can be used for body parts */
sealed trait BodyPartEntity extends HttpEntity with japi.BodyPartEntity {
  def withContentType(contentType: ContentType): BodyPartEntity
}
/* An entity that can be used for requests */
sealed trait RequestEntity extends HttpEntity with japi.RequestEntity with ResponseEntity {
  def withContentType(contentType: ContentType): RequestEntity

  override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): RequestEntity
}
/* An entity that can be used for responses */
sealed trait ResponseEntity extends HttpEntity with japi.ResponseEntity {
  def withContentType(contentType: ContentType): ResponseEntity

  override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): ResponseEntity
}
/* An entity that can be used for requests, responses, and body parts */
sealed trait UniversalEntity extends japi.UniversalEntity with MessageEntity with BodyPartEntity {
  def withContentType(contentType: ContentType): UniversalEntity
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
  def apply(contentType: ContentType, contentLength: Long, data: Source[ByteString]): UniversalEntity =
    if (contentLength == 0) empty(contentType) else Default(contentType, contentLength, data)

  def apply(contentType: ContentType, file: File): UniversalEntity = {
    val fileLength = file.length
    if (fileLength > 0) Default(contentType, fileLength, ???) // FIXME: attach from-file-Publisher
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
    extends japi.HttpEntityStrict with UniversalEntity {

    def isKnownEmpty: Boolean = data.isEmpty

    def dataBytes: Source[ByteString] = Source(data :: Nil)

    override def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: FlowMaterializer) =
      FastFuture.successful(this)

    override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): MessageEntity = {
      try {
        val t = transformer()
        val newData = (t.onNext(data) ++ t.onTermination(None)).join
        copy(data = newData)
      } catch {
        case NonFatal(ex) ⇒
          Chunked(contentType, Source.failed(ex))
      }
    }

    def withContentType(contentType: ContentType): Strict =
      if (contentType == this.contentType) this else copy(contentType = contentType)
  }

  /**
   * The model for the entity of a "regular" unchunked HTTP message with a known non-zero length.
   */
  final case class Default(contentType: ContentType,
                           contentLength: Long,
                           data: Source[ByteString])
    extends japi.HttpEntityDefault with UniversalEntity {

    require(contentLength > 0, "contentLength must be positive (use `HttpEntity.empty(contentType)` for empty entities)")
    def isKnownEmpty = false
    override def isDefault: Boolean = true

    def dataBytes: Source[ByteString] = data

    override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): Chunked = {
      val chunks = data.transform("transformDataBytes-Default", () ⇒ transformer().map(Chunk(_): ChunkStreamPart))

      HttpEntity.Chunked(contentType, chunks)
    }

    def withContentType(contentType: ContentType): Default =
      if (contentType == this.contentType) this else copy(contentType = contentType)
  }

  /**
   * Supertype of CloseDelimited and IndefiniteLength.
   *
   * INTERNAL API
   */
  private[http] sealed trait WithoutKnownLength extends HttpEntity {
    def contentType: ContentType
    def data: Source[ByteString]

    def isKnownEmpty = data eq EmptyPublisher

    def dataBytes: Source[ByteString] = data
  }

  /**
   * The model for the entity of an HTTP response that is terminated by the server closing the connection.
   * The content-length of such responses is unknown at the time the response headers have been received.
   * Note that this type of HttpEntity can only be used for HttpResponses.
   */
  final case class CloseDelimited(contentType: ContentType, data: Source[ByteString])
    extends japi.HttpEntityCloseDelimited with ResponseEntity with WithoutKnownLength {
    type Self = CloseDelimited

    override def isCloseDelimited: Boolean = true
    def withContentType(contentType: ContentType): CloseDelimited =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): CloseDelimited =
      HttpEntity.CloseDelimited(contentType,
        data.transform("transformDataBytes-CloseDelimited", transformer))
  }

  /**
   * The model for the entity of a BodyPart with an indefinite length.
   * Note that this type of HttpEntity can only be used for BodyParts.
   */
  final case class IndefiniteLength(contentType: ContentType, data: Source[ByteString])
    extends japi.HttpEntityIndefiniteLength with BodyPartEntity with WithoutKnownLength {

    override def isIndefiniteLength: Boolean = true
    def withContentType(contentType: ContentType): IndefiniteLength =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): IndefiniteLength =
      HttpEntity.IndefiniteLength(contentType,
        data.transform("transformDataBytes-IndefiniteLength", transformer))
  }

  /**
   * The model for the entity of a chunked HTTP message (with `Transfer-Encoding: chunked`).
   */
  final case class Chunked(contentType: ContentType, chunks: Source[ChunkStreamPart])
    extends japi.HttpEntityChunked with MessageEntity {

    def isKnownEmpty = chunks eq EmptyPublisher
    override def isChunked: Boolean = true

    def dataBytes: Source[ByteString] =
      chunks.map(_.data).filter(_.nonEmpty)

    override def transformDataBytes(transformer: () ⇒ Transformer[ByteString, ByteString]): Chunked = {
      val newChunks =
        chunks.transform("transformDataBytes-Chunked", () ⇒ new Transformer[ChunkStreamPart, ChunkStreamPart] {
          val byteTransformer = transformer()
          var sentLastChunk = false

          override def isComplete: Boolean = byteTransformer.isComplete

          def onNext(element: ChunkStreamPart): immutable.Seq[ChunkStreamPart] = element match {
            case Chunk(data, ext) ⇒ Chunk(byteTransformer.onNext(data).join, ext) :: Nil
            case l: LastChunk ⇒
              sentLastChunk = true
              Chunk(byteTransformer.onTermination(None).join) :: l :: Nil
          }
          override def onError(cause: scala.Throwable): Unit = byteTransformer.onError(cause)
          override def onTermination(e: Option[Throwable]): immutable.Seq[ChunkStreamPart] = {
            val remaining =
              if (e.isEmpty && !sentLastChunk) byteTransformer.onTermination(None)
              else if (e.isDefined /* && sentLastChunk */ ) byteTransformer.onTermination(e)
              else Nil

            if (remaining.nonEmpty) Chunk(remaining.join) :: Nil
            else Nil
          }

          override def cleanup(): Unit = byteTransformer.cleanup()
        })

      HttpEntity.Chunked(contentType, newChunks)
    }

    def withContentType(contentType: ContentType): Chunked =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    /** Java API */
    def getChunks: Source[japi.ChunkStreamPart] = chunks.asInstanceOf[Source[japi.ChunkStreamPart]]
  }
  object Chunked {
    /**
     * Returns a ``Chunked`` entity where one Chunk is produced for every non-empty ByteString of the given
     * ``Publisher[ByteString]``.
     */
    def fromData(contentType: ContentType, chunks: Source[ByteString]): Chunked =
      Chunked(contentType, chunks.collect[ChunkStreamPart] {
        case b: ByteString if b.nonEmpty ⇒ Chunk(b)
      })
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

    /** Java API */
    def getTrailerHeaders: JIterable[japi.HttpHeader] = java.util.Collections.emptyList[japi.HttpHeader]
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
    def getTrailerHeaders: JIterable[japi.HttpHeader] = trailer.asJava
  }
  object LastChunk extends LastChunk("", Nil)
}