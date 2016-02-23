/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import scala.reflect.ClassTag
import akka.http.javadsl.server.Unmarshaller
import akka.http.scaladsl.unmarshalling.FromMessageUnmarshaller

/**
 * INTERNAL API
 *
 */
private[http] case class UnmarshallerImpl[T](scalaUnmarshaller: FromMessageUnmarshaller[T])(implicit val classTag: ClassTag[T])
  extends Unmarshaller[T]
