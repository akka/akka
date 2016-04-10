/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.unmarshalling

import akka.http.scaladsl.unmarshalling.Unmarshaller.EitherUnmarshallingException
import akka.http.scaladsl.util.FastFuture
import akka.stream.impl.ConstantFun

import scala.concurrent.Future
import scala.reflect.ClassTag

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

  /**
   * Enables using [[Either]] to encode the following unmarshalling logic:
   * Attempt unmarshalling the entity as as `R` first (yielding `R`),
   * and if it fails attempt unmarshalling as `L` (yielding `Left`).
   *
   * Note that the Either's "R" type will be attempted first (as Left is often considered as the "failed case" in Either).
   */
  // format: OFF
  implicit def eitherUnmarshaller[L, R](implicit ua: FromEntityUnmarshaller[L], rightTag: ClassTag[R],
                                                 ub: FromEntityUnmarshaller[R], leftTag: ClassTag[L]): FromEntityUnmarshaller[Either[L, R]] =
    Unmarshaller.withMaterializer { implicit ex ⇒ implicit mat ⇒ value ⇒
      import akka.http.scaladsl.util.FastFuture._
      @inline def right = ub(value).fast.map(Right(_))
      @inline def fallbackLeft: PartialFunction[Throwable, Future[Either[L, R]]] = { case rightFirstEx ⇒
        val left = ua(value).fast.map(Left(_))

        // combine EitherUnmarshallingException by carring both exceptions
        left.transform(
          s = ConstantFun.scalaIdentityFunction,
          f = leftSecondEx => new EitherUnmarshallingException(
            rightClass = rightTag.runtimeClass, right = rightFirstEx,
            leftClass = leftTag.runtimeClass, left = leftSecondEx)
          )
      }

      right.recoverWith(fallbackLeft)
    }
  // format: ON

}