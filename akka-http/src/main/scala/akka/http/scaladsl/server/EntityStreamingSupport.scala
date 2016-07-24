/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.scaladsl.server

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.common.{ FramingWithContentType, SourceRenderingMode }
import akka.http.scaladsl.model.{ ContentTypeRange, ContentTypes, MediaRange, MediaRanges }
import akka.stream.scaladsl.{ Flow, Framing }
import akka.util.ByteString
import com.typesafe.config.Config

/**
 * Entity streaming support, independent of used Json parsing library etc.
 *
 * Can be extended by various Support traits (e.g. "SprayJsonSupport"),
 * in order to provide users with both `framing` (this trait) and `marshalling`
 * (implemented by a library) by using a single trait.
 */
trait EntityStreamingSupport extends EntityStreamingSupportBase {

  /**
   * Implement as `implicit val` with required framing implementation, for example in
   * the case of streaming JSON uploads it could be `bracketCountingJsonFraming(maximumObjectLength)`.
   */
  def incomingEntityStreamFraming: FramingWithContentType

  /**
   * Implement as `implicit val` with the rendering mode to be used when redering `Source` instances.
   * For example for JSON it could be [[akka.http.scaladsl.common.JsonSourceRenderingMode.CompactArray]]
   * or [[akka.http.scaladsl.common.JsonSourceRenderingMode.LineByLine]].
   */
  def outgoingEntityStreamRendering: SourceRenderingMode
}

trait EntityStreamingSupportBase {
  /** `application/json` specific Framing implementation */
  def bracketCountingJsonFraming(maximumObjectLength: Int): FramingWithContentType =
    new ApplicationJsonBracketCountingFraming(maximumObjectLength)

  /**
   * Frames incoming `text / *` entities on a line-by-line basis.
   * Useful for accepting `text/csv` uploads as a stream of rows.
   */
  def newLineFraming(maximumObjectLength: Int, supportedContentTypes: ContentTypeRange): FramingWithContentType =
    new FramingWithContentType {
      override final val flow: Flow[ByteString, ByteString, NotUsed] =
        Flow[ByteString].via(Framing.delimiter(ByteString("\n"), maximumObjectLength))

      override final val supported: ContentTypeRange =
        ContentTypeRange(MediaRanges.`text/*`)
    }
}

/**
 * Entity streaming support, independent of used Json parsing library etc.
 *
 * Can be extended by various Support traits (e.g. "SprayJsonSupport"),
 * in order to provide users with both `framing` (this trait) and `marshalling`
 * (implemented by a library) by using a single trait.
 */
object EntityStreamingSupport extends EntityStreamingSupportBase

final class ApplicationJsonBracketCountingFraming(maximumObjectLength: Int) extends FramingWithContentType {
  override final val flow = Flow[ByteString].via(akka.stream.scaladsl.JsonFraming.bracketCounting(maximumObjectLength))
  override final val supported = ContentTypeRange(ContentTypes.`application/json`)
}
