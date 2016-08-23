/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.NotUsed
import akka.event.Logging
import akka.http.javadsl.{ common, model ⇒ jm }
import akka.http.scaladsl.model.{ ContentType, ContentTypeRange, ContentTypes }
import akka.stream.scaladsl.Flow
import akka.util.ByteString

final class JsonEntityStreamingSupport private[akka] (
  maxObjectSize:       Int,
  val supported:       ContentTypeRange,
  val contentType:     ContentType,
  val framingRenderer: Flow[ByteString, ByteString, NotUsed],
  val parallelism:     Int,
  val unordered:       Boolean
) extends common.JsonEntityStreamingSupport {
  import akka.http.impl.util.JavaMapping.Implicits._

  def this(maxObjectSize: Int) =
    this(
      maxObjectSize,
      ContentTypeRange(ContentTypes.`application/json`),
      ContentTypes.`application/json`,
      Flow[ByteString].intersperse(ByteString("["), ByteString(","), ByteString("]")),
      1, false)

  override val framingDecoder: Flow[ByteString, ByteString, NotUsed] =
    akka.stream.scaladsl.JsonFraming.objectScanner(maxObjectSize)

  override def withFramingRendererFlow(framingRendererFlow: akka.stream.javadsl.Flow[ByteString, ByteString, NotUsed]): JsonEntityStreamingSupport =
    withFramingRenderer(framingRendererFlow.asScala)
  def withFramingRenderer(framingRendererFlow: Flow[ByteString, ByteString, NotUsed]): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, supported, contentType, framingRendererFlow, parallelism, unordered)

  override def withContentType(ct: jm.ContentType): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, supported, ct.asScala, framingRenderer, parallelism, unordered)
  override def withSupported(range: jm.ContentTypeRange): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, range.asScala, contentType, framingRenderer, parallelism, unordered)
  override def withParallelMarshalling(parallelism: Int, unordered: Boolean): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, supported, contentType, framingRenderer, parallelism, unordered)

  override def toString = s"""${Logging.simpleName(getClass)}($maxObjectSize, $supported, $contentType)"""

}
