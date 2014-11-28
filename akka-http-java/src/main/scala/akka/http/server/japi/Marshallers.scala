/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi

import akka.http.marshalling.ToResponseMarshaller
import akka.http.server.japi.impl.MarshallerImpl

/**
 * A collection of predefined marshallers.
 */
object Marshallers {
  def STRING: Marshaller[String] = MarshallerImpl(implicit ctx ⇒ implicitly[ToResponseMarshaller[String]])
}
