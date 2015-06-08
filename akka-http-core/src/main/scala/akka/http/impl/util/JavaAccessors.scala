/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.io.File

import JavaMapping.Implicits._
import akka.http.impl.model.JavaUri
import akka.http.javadsl.model._
import akka.http.scaladsl.model

/**
 *  INTERNAL API
 *
 *  Accessors for constructors with default arguments to be used from the Java implementation
 */
object JavaAccessors {
  /** INTERNAL API */
  def HttpRequest(): HttpRequest = model.HttpRequest()

  /** INTERNAL API */
  def HttpRequest(uri: String): HttpRequest = model.HttpRequest(uri = uri)

  /** INTERNAL API */
  def HttpResponse(): HttpResponse = model.HttpResponse()

  /** INTERNAL API */
  def Uri(uri: model.Uri): Uri = JavaUri(uri)

  /** INTERNAL API */
  def HttpEntity(contentType: ContentType, file: File): UniversalEntity =
    model.HttpEntity(contentType.asScala, file)
}
