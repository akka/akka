/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.NotUsed
import akka.event.Logging
import akka.http.scaladsl.model.ContentTypeRange
import akka.stream.scaladsl.{ Flow, Framing }
import akka.util.ByteString

/**
 * Wraps a framing [[akka.stream.scaladsl.Flow]] (as provided by [[Framing]] for example)
 * that chunks up incoming [[akka.util.ByteString]] according to some [[akka.http.javadsl.model.ContentType]]
 * specific logic.
 */
abstract class FramingWithContentType extends akka.http.javadsl.common.FramingWithContentType {
  def flow: Flow[ByteString, ByteString, NotUsed]
  override final def getFlow = flow.asJava
  override def supported: ContentTypeRange
  override def matches(ct: akka.http.javadsl.model.ContentType): Boolean = supported.matches(ct)

  override def toString = s"${Logging.simpleName(getClass)}($supported)"
}
