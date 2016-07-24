/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.http.javadsl.model.ContentType.WithCharset
import akka.http.javadsl.model.ContentTypes
import akka.util.ByteString

/**
 * Specialised rendering mode for streaming elements as CSV.
 */
trait CsvSourceRenderingMode extends SourceRenderingMode {
  override val contentType: WithCharset =
    ContentTypes.TEXT_CSV_UTF8
}

object CsvSourceRenderingModes {

  /**
   * Render sequence of values as row-by-row ('\n' separated) series of values.
   */
  val create: CsvSourceRenderingMode =
    new CsvSourceRenderingMode {
      override def between: ByteString = ByteString("\n")
      override def end: ByteString = ByteString.empty
      override def start: ByteString = ByteString.empty
    }

  /**
   * Render sequence of values as row-by-row (with custom row separator,
   * e.g. if you need to use '\r\n' instead of '\n') series of values.
   */
  def custom(rowSeparator: String): CsvSourceRenderingMode =
    new CsvSourceRenderingMode {
      override def between: ByteString = ByteString(rowSeparator)
      override def end: ByteString = ByteString.empty
      override def start: ByteString = ByteString.empty
    }
}
