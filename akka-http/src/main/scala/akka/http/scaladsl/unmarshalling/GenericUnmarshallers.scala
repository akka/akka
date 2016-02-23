/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.unmarshalling

import akka.http.scaladsl.util.FastFuture

trait GenericUnmarshallers extends LowerPriorityGenericUnmarshallers {

  implicit def liftToTargetOptionUnmarshaller[A, B](um: Unmarshaller[A, B]): Unmarshaller[A, Option[B]] =
    targetOptionUnmarshaller(um)
  implicit def targetOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B]): Unmarshaller[A, Option[B]] =
    um map (Some(_)) withDefaultValue None
}

sealed trait LowerPriorityGenericUnmarshallers {

  implicit def messageUnmarshallerFromEntityUnmarshaller[T](implicit um: FromEntityUnmarshaller[T]): FromMessageUnmarshaller[T] =
    Unmarshaller.withMaterializer { implicit ec ⇒ implicit mat ⇒ request ⇒ um(request.entity) }

  implicit def liftToSourceOptionUnmarshaller[A, B](um: Unmarshaller[A, B]): Unmarshaller[Option[A], B] =
    sourceOptionUnmarshaller(um)
  implicit def sourceOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B]): Unmarshaller[Option[A], B] =
    Unmarshaller.withMaterializer(implicit ec ⇒ implicit mat ⇒ {
      case Some(a) ⇒ um(a)
      case None    ⇒ FastFuture.failed(Unmarshaller.NoContentException)
    })
}