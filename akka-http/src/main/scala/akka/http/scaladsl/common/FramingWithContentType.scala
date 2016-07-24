/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.NotUsed
import akka.event.Logging
import akka.http.scaladsl.model.{ ContentType, ContentTypeRange }
import akka.stream.scaladsl.{ Flow, Framing }
import akka.util.ByteString

/**
 * Same as [[akka.stream.scaladsl.Framing]] but additionally can express which [[ContentType]] it supports,
 * which can be used to reject routes if content type does not match used framing.
 */
abstract class FramingWithContentType extends akka.http.javadsl.common.FramingWithContentType with Framing {
  def flow: Flow[ByteString, ByteString, NotUsed]
  override def supported: ContentTypeRange
  override def matches(ct: akka.http.javadsl.model.ContentType): Boolean = supported.matches(ct)

  override def toString = s"${Logging.simpleName(getClass)}($supported)"
}
