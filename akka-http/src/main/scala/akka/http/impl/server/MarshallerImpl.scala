/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import scala.concurrent.ExecutionContext
import akka.http.javadsl.server.Marshaller
import akka.http.scaladsl.marshalling

/**
 * INTERNAL API
 */
// FIXME: too lenient visibility, currently used to implement Java marshallers, needs proper API, see #16439
case class MarshallerImpl[T](scalaMarshaller: ExecutionContext ⇒ marshalling.ToResponseMarshaller[T]) extends Marshaller[T]
