/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import scala.reflect.ClassTag
import akka.http.javadsl.server.{ RequestContext, RequestVal }
import akka.http.impl.util.JavaMapping.Implicits._

/**
 * INTERNAL API
 */
private[http] trait ExtractionImplBase[T] extends RequestVal[T] {
  protected[http] implicit def classTag: ClassTag[T]
  def resultClass: Class[T] = classTag.runtimeClass.asInstanceOf[Class[T]]

  def get(ctx: RequestContext): T =
    ctx.request.asScala.header[ExtractionMap].flatMap(_.get(this))
      .getOrElse(throw new RuntimeException(s"Value wasn't extracted! $this"))
}

private[http] abstract class ExtractionImpl[T](implicit val classTag: ClassTag[T]) extends ExtractionImplBase[T]