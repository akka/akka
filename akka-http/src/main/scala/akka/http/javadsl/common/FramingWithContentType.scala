/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.http.javadsl.model.ContentTypeRange
import akka.stream.javadsl.Framing

trait FramingWithContentType extends Framing { self ⇒
  import akka.http.impl.util.JavaMapping.Implicits._

  override def asScala: akka.http.scaladsl.common.FramingWithContentType =
    this match {
      case f: akka.http.scaladsl.common.FramingWithContentType ⇒ f
      case _ ⇒ new akka.http.scaladsl.common.FramingWithContentType {
        override def flow = self.getFlow.asScala
        override def supported = self.supported.asScala
      }
    }

  def supported: ContentTypeRange
  def matches(ct: akka.http.javadsl.model.ContentType): Boolean = supported.matches(ct)
}
