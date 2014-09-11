/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.concurrent.ExecutionContext

trait GenericUnmarshallers extends UnmarshallerLifting {

  implicit def targetOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B], ec: ExecutionContext): Unmarshaller[A, Option[B]] =
    um map (Some(_)) withDefaultValue None

  implicit def sourceOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B], ec: ExecutionContext): Unmarshaller[Option[A], B] =
    Unmarshaller {
      case Some(a) ⇒ um(a)
      case None    ⇒ UnmarshallingError.ContentExpected
    }
}

object GenericUnmarshallers extends GenericUnmarshallers