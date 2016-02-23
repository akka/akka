/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.concurrent.Future
import scala.util.{ Try, Failure, Success }
import akka.http.scaladsl.util.FastFuture
import FastFuture._

trait GenericMarshallers extends LowPriorityToResponseMarshallerImplicits {

  implicit def throwableMarshaller[T]: Marshaller[Throwable, T] = Marshaller(_ ⇒ FastFuture.failed)

  implicit def optionMarshaller[A, B](implicit m: Marshaller[A, B], empty: EmptyValue[B]): Marshaller[Option[A], B] =
    Marshaller { implicit ec ⇒
      {
        case Some(value) ⇒ m(value)
        case None        ⇒ FastFuture.successful(Marshalling.Opaque(() ⇒ empty.emptyValue) :: Nil)
      }
    }

  implicit def eitherMarshaller[A1, A2, B](implicit m1: Marshaller[A1, B], m2: Marshaller[A2, B]): Marshaller[Either[A1, A2], B] =
    Marshaller { implicit ec ⇒
      {
        case Left(a1)  ⇒ m1(a1)
        case Right(a2) ⇒ m2(a2)
      }
    }

  implicit def futureMarshaller[A, B](implicit m: Marshaller[A, B]): Marshaller[Future[A], B] =
    Marshaller(implicit ec ⇒ _.fast.flatMap(m(_)))

  implicit def tryMarshaller[A, B](implicit m: Marshaller[A, B]): Marshaller[Try[A], B] =
    Marshaller { implicit ec ⇒
      {
        case Success(value) ⇒ m(value)
        case Failure(error) ⇒ FastFuture.failed(error)
      }
    }
}

object GenericMarshallers extends GenericMarshallers