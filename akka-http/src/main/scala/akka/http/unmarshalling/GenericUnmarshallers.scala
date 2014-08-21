/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.http.unmarshalling.Unmarshalling._

import scala.concurrent.{ Future, ExecutionContext }

trait GenericUnmarshallers extends UnmarshallerLifting {

  implicit def targetOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B], ec: ExecutionContext): Unmarshaller[A, Option[B]] =
    um.mapUnmarshalling {
      case Success(b)      ⇒ Success(Some(b))
      case ContentExpected ⇒ Success(None)
      case x: Failure      ⇒ x
    }

  implicit def sourceOptionUnmarshaller[A, B](implicit um: Unmarshaller[A, B], ec: ExecutionContext): Unmarshaller[Option[A], B] =
    Unmarshaller {
      case Some(a) ⇒ um(a)
      case None    ⇒ Future.successful(ContentExpected)
    }
}

object GenericUnmarshallers extends GenericUnmarshallers