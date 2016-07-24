/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.http.scaladsl.model.ContentType

trait SourceRenderingMode extends akka.http.javadsl.common.SourceRenderingMode {
  override def contentType: ContentType
}
