/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.http.model._

trait UnmarshallerLifting {

  implicit def entity2message[A <: HttpMessage, B](implicit um: FromEntityUnmarshaller[B]): Unmarshaller[A, B] =
    Unmarshaller { message ⇒ um(message.entity) }

  implicit def message2request[T](implicit um: FromMessageUnmarshaller[T]): FromRequestUnmarshaller[T] =
    Unmarshaller { request ⇒ um(request) }

  implicit def message2response[T](implicit um: FromMessageUnmarshaller[T]): FromResponseUnmarshaller[T] =
    Unmarshaller { response ⇒ um(response) }
}

object UnmarshallerLifting extends UnmarshallerLifting