/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import akka.http.scaladsl.model.headers.{ ModeledCustomHeader, ModeledCustomHeaderCompanion }

import scala.util.{ Success, Try }

// no support for modeled headers in the Java DSL yet, so this has to live here

object SampleCustomHeader extends ModeledCustomHeaderCompanion[SampleCustomHeader] {
  override def name: String = "X-Sample-Custom-Header"
  override def parse(value: String): Try[SampleCustomHeader] = Success(new SampleCustomHeader(value))
}

class SampleCustomHeader(val value: String) extends ModeledCustomHeader[SampleCustomHeader] {
  override def companion: ModeledCustomHeaderCompanion[SampleCustomHeader] = SampleCustomHeader
  override def renderInResponses(): Boolean = true
  override def renderInRequests(): Boolean = true
}
