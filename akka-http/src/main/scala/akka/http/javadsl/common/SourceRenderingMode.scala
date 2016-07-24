/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.http.javadsl.model.ContentType
import akka.util.ByteString

/**
 * Defines how to render a [[akka.stream.javadsl.Source]] into a raw [[ByteString]]
 * output.
 *
 * This can be used to render a source into an [[akka.http.scaladsl.model.HttpEntity]].
 */
trait SourceRenderingMode {
  def contentType: ContentType

  def start: ByteString
  def between: ByteString
  def end: ByteString
}
