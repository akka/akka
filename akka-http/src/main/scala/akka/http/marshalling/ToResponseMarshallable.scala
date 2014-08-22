/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.concurrent.{ ExecutionContext, Future }

import akka.http.Marshal
import akka.http.model._

/** Something that can later be marshalled into a response */
trait ToResponseMarshallable {
  type T
  def value: T
  implicit def marshaller: ToResponseMarshaller[T]
  implicit def executionContext: ExecutionContext

  def marshalFor(request: HttpRequest): Future[HttpResponse] = new Marshal(value).toResponseFor(request)
}

object ToResponseMarshallable {
  implicit def isMarshallable[A](_value: A)(implicit _marshaller: ToResponseMarshaller[A], ec: ExecutionContext): ToResponseMarshallable =
    new ToResponseMarshallable {
      type T = A
      def value: T = _value
      def marshaller: ToResponseMarshaller[T] = _marshaller
      def executionContext: ExecutionContext = ec
    }
  implicit def marshallableIsMarshallable: ToResponseMarshaller[ToResponseMarshallable] =
    Marshaller[ToResponseMarshallable, HttpResponse] { value ⇒
      value.marshaller(value.value)
    }
}