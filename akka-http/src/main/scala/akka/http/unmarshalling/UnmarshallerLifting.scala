/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

trait UnmarshallerLifting {
  implicit def entity2request[T](implicit um: FromEntityUnmarshaller[T]): FromRequestUnmarshaller[T] =
    Unmarshaller { request ⇒ um(request.entity) }

  implicit def entity2response[T](implicit um: FromEntityUnmarshaller[T]): FromResponseUnmarshaller[T] =
    Unmarshaller { response ⇒ um(response.entity) }

  implicit def message2request[T](implicit um: FromMessageUnmarshaller[T]): FromRequestUnmarshaller[T] =
    Unmarshaller { request ⇒ um(request) }

  implicit def message2response[T](implicit um: FromMessageUnmarshaller[T]): FromResponseUnmarshaller[T] =
    Unmarshaller { response ⇒ um(response) }
}

object UnmarshallerLifting extends UnmarshallerLifting