/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.NotUsed
import akka.event.Logging
import akka.http.javadsl.{ common, model ⇒ jm }
import akka.http.scaladsl.model.{ ContentType, ContentTypeRange, ContentTypes }
import akka.stream.scaladsl.{ Flow, Framing }
import akka.util.ByteString

final class CsvEntityStreamingSupport private[akka] (
  maxLineLength:       Int,
  val supported:       ContentTypeRange,
  val contentType:     ContentType,
  val framingRenderer: Flow[ByteString, ByteString, NotUsed],
  val parallelism:     Int,
  val unordered:       Boolean
) extends common.CsvEntityStreamingSupport {
  import akka.http.impl.util.JavaMapping.Implicits._

  def this(maxObjectSize: Int) =
    this(
      maxObjectSize,
      ContentTypeRange(ContentTypes.`text/csv(UTF-8)`),
      ContentTypes.`text/csv(UTF-8)`,
      Flow[ByteString].intersperse(ByteString("\n")),
      1, false)

  override val framingDecoder: Flow[ByteString, ByteString, NotUsed] =
    Framing.delimiter(ByteString("\n"), maxLineLength)

  override def withFramingRendererFlow(framingRendererFlow: akka.stream.javadsl.Flow[ByteString, ByteString, NotUsed]): CsvEntityStreamingSupport =
    withFramingRenderer(framingRendererFlow.asScala)
  def withFramingRenderer(framingRendererFlow: Flow[ByteString, ByteString, NotUsed]): CsvEntityStreamingSupport =
    new CsvEntityStreamingSupport(maxLineLength, supported, contentType, framingRendererFlow, parallelism, unordered)

  override def withContentType(ct: jm.ContentType): CsvEntityStreamingSupport =
    new CsvEntityStreamingSupport(maxLineLength, supported, ct.asScala, framingRenderer, parallelism, unordered)
  override def withSupported(range: jm.ContentTypeRange): CsvEntityStreamingSupport =
    new CsvEntityStreamingSupport(maxLineLength, range.asScala, contentType, framingRenderer, parallelism, unordered)
  override def withParallelMarshalling(parallelism: Int, unordered: Boolean): CsvEntityStreamingSupport =
    new CsvEntityStreamingSupport(maxLineLength, supported, contentType, framingRenderer, parallelism, unordered)

  override def toString = s"""${Logging.simpleName(getClass)}($maxLineLength, $supported, $contentType)"""
}
