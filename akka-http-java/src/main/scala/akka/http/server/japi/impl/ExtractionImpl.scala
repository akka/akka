/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import akka.http.model.japi.JavaMapping.Implicits._

import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
private[japi] trait ExtractionImplBase[T] extends RequestVal[T] {
  protected[japi] implicit def classTag: ClassTag[T]
  def resultClass: Class[T] = classTag.runtimeClass.asInstanceOf[Class[T]]

  def get(ctx: RequestContext): T =
    ctx.request.asScala.header[ExtractionMap].flatMap(_.get(this))
      .getOrElse(throw new RuntimeException(s"Value wasn't extracted! $this"))
}

private[japi] abstract class ExtractionImpl[T](implicit val classTag: ClassTag[T]) extends ExtractionImplBase[T]