/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import akka.http.unmarshalling.FromMessageUnmarshaller
import akka.stream.ActorFlowMaterializer

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

/**
 * INTERNAL API
 *
 */
// FIXME: too lenient visibility, currently used to implement Java marshallers, needs proper API, see #16439
case class UnmarshallerImpl[T](scalaUnmarshaller: (ExecutionContext, ActorFlowMaterializer) ⇒ FromMessageUnmarshaller[T])(implicit val classTag: ClassTag[T])
  extends Unmarshaller[T]
