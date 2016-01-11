/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Failure, Success }
import akka.http.util.Deferrable

trait GenericMarshallers extends LowPriorityToResponseMarshallerImplicits {

  implicit def throwableMarshaller[T]: Marshaller[Throwable, T] = Marshaller(Deferrable.failed)

  implicit def optionMarshaller[A, B](implicit m: Marshaller[A, B], empty: EmptyValue[B]): Marshaller[Option[A], B] =
    Marshaller {
      case Some(value) ⇒ m(value)
      case None        ⇒ Deferrable(Marshalling.Opaque(() ⇒ empty.emptyValue))
    }

  implicit def eitherMarshaller[A1, A2, B](implicit m1: Marshaller[A1, B], m2: Marshaller[A2, B]): Marshaller[Either[A1, A2], B] =
    Marshaller {
      case Left(a1)  ⇒ m1(a1)
      case Right(a2) ⇒ m2(a2)
    }

  implicit def deferrableMarshaller[A, B](implicit m: Marshaller[A, B], ec: ExecutionContext): Marshaller[Deferrable[A], B] =
    Marshaller(_ flatMap m.apply)

  implicit def futureMarshaller[A, B](implicit m: Marshaller[A, B], ec: ExecutionContext): Marshaller[Future[A], B] =
    Marshaller(Deferrable(_) flatMap m.apply)

  implicit def tryMarshaller[A, B](implicit m: Marshaller[A, B]): Marshaller[Try[A], B] =
    Marshaller {
      case Success(value) ⇒ m(value)
      case Failure(error) ⇒ Deferrable.failed(error)
    }
}

object GenericMarshallers extends GenericMarshallers