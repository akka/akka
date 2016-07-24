/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.NotUsed
import akka.http.javadsl.common.{ FramingWithContentType, SourceRenderingMode }
import akka.http.javadsl.model.{ ContentTypeRange, MediaRanges }
import akka.http.scaladsl.server.ApplicationJsonBracketCountingFraming
import akka.stream.javadsl.{ Flow, Framing }
import akka.util.ByteString

/**
 * Entity streaming support, independent of used Json parsing library etc.
 *
 * Can be extended by various Support traits (e.g. "SprayJsonSupport"),
 * in order to provide users with both `framing` (this trait) and `marshalling`
 * (implemented by a library) by using a single trait.
 */
object EntityStreamingSupport {
  // in the ScalaDSL version we make users implement abstract methods that are supposed to be
  // implicit vals. This helps to guide in implementing the needed values, however in Java that would not really help.

  /** `application/json` specific Framing implementation */
  def bracketCountingJsonFraming(maximumObjectLength: Int): FramingWithContentType =
    new ApplicationJsonBracketCountingFraming(maximumObjectLength)

  /**
   * Frames incoming `text / *` entities on a line-by-line basis.
   * Useful for accepting `text/csv` uploads as a stream of rows.
   */
  def newLineFraming(maximumObjectLength: Int, supportedContentTypes: ContentTypeRange): FramingWithContentType =
    new FramingWithContentType {
      override final val getFlow: Flow[ByteString, ByteString, NotUsed] =
        Flow.of(classOf[ByteString]).via(Framing.delimiter(ByteString("\n"), maximumObjectLength))

      override final val supported: ContentTypeRange =
        akka.http.scaladsl.model.ContentTypeRange(akka.http.scaladsl.model.MediaRanges.`text/*`)
    }
}
