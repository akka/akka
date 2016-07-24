/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.NotUsed
import akka.event.Logging
import akka.http.javadsl.model.ContentTypeRange
import akka.stream.javadsl.{ Flow, Framing }
import akka.util.ByteString

/**
 * Wraps a framing [[akka.stream.javadsl.Flow]] (as provided by [[Framing]] for example)
 * that chunks up incoming [[akka.util.ByteString]] according to some [[akka.http.javadsl.model.ContentType]]
 * specific logic.
 */
abstract class FramingWithContentType { self ⇒
  import akka.http.impl.util.JavaMapping.Implicits._

  def getFlow: Flow[ByteString, ByteString, NotUsed]

  def asScala: akka.http.scaladsl.common.FramingWithContentType =
    this match {
      case f: akka.http.scaladsl.common.FramingWithContentType ⇒ f
      case _ ⇒ new akka.http.scaladsl.common.FramingWithContentType {
        override def flow = self.getFlow.asScala
        override def supported = self.supported.asScala
      }
    }

  def supported: ContentTypeRange
  def matches(ct: akka.http.javadsl.model.ContentType): Boolean = supported.matches(ct)

  override def toString = s"${Logging.simpleName(getClass)}($supported)"
}
