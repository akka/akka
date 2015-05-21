/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.impl.server.MarshallerImpl

/**
 * A collection of predefined marshallers.
 */
object Marshallers {
  def STRING: Marshaller[String] = MarshallerImpl(implicit ctx ⇒ implicitly[ToResponseMarshaller[String]])
}
