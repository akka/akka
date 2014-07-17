/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

import akka.http.model._

import akka.http.util._

abstract class SimpleUnmarshaller[T] extends Unmarshaller[T] {
  val canUnmarshalFrom: Seq[ContentTypeRange]

  def apply(entity: HttpEntity): Deserialized[T] =
    entity match {
      case HttpEntity.Empty ⇒ unmarshal(entity)
      case x: HttpEntity if canUnmarshalFrom.exists(_.matches(x.contentType)) ⇒ unmarshal(entity)
      case _ ⇒ Future.failed(UnsupportedContentType(canUnmarshalFrom.map(_.value).mkString("Expected '", "' or '", "'")))
    }

  protected def unmarshal(entity: HttpEntity): Future[T]

  /**
   * Helper method for turning exceptions occuring during evaluation of the named parameter into
   * [[akka.http.unmarshalling.MalformedContent]] instances.
   */
  protected def protect(f: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    f.recoverWith {
      case NonFatal(ex) ⇒ Future.failed(MalformedContent(ex.getMessage.nullAsEmpty, ex))
    }
}