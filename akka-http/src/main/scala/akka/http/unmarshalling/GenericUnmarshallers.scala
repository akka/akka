/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.concurrent.ExecutionContext
import akka.http.util.FastFuture

trait GenericUnmarshallers extends LowerPriorityGenericUnmarshallers {

  implicit def liftToTargetOptionUnmarshaller[A, B](um: Unmarshaller[A, B])(implicit ec: ExecutionContext): Unmarshaller[A, Option[B]] =
    targetOptionUnmarshaller(um, ec)
  implicit def targetOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B], ec: ExecutionContext): Unmarshaller[A, Option[B]] =
    um map (Some(_)) withDefaultValue None
}

sealed trait LowerPriorityGenericUnmarshallers {

  implicit def messageUnmarshallerFromEntityUnmarshaller[T](implicit um: FromEntityUnmarshaller[T]): FromMessageUnmarshaller[T] =
    Unmarshaller { request ⇒ um(request.entity) }

  implicit def liftToSourceOptionUnmarshaller[A, B](um: Unmarshaller[A, B]): Unmarshaller[Option[A], B] =
    sourceOptionUnmarshaller(um)
  implicit def sourceOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B]): Unmarshaller[Option[A], B] =
    Unmarshaller {
      case Some(a) ⇒ um(a)
      case None    ⇒ FastFuture.failed(Unmarshaller.NoContentException)
    }
}