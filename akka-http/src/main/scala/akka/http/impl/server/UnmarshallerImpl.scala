/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import akka.stream.Materializer
import akka.http.javadsl.server.Unmarshaller
import akka.http.scaladsl.unmarshalling.FromMessageUnmarshaller

/**
 * INTERNAL API
 *
 */
// FIXME: too lenient visibility, currently used to implement Java marshallers, needs proper API, see #16439
case class UnmarshallerImpl[T](scalaUnmarshaller: (ExecutionContext, Materializer) ⇒ FromMessageUnmarshaller[T])(implicit val classTag: ClassTag[T])
  extends Unmarshaller[T]
