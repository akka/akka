/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.concurrent.{ Future, ExecutionContext }
import akka.http.scaladsl.model._

/** Something that can later be marshalled into a response */
trait ToResponseMarshallable {
  type T
  def value: T
  implicit def marshaller: ToResponseMarshaller[T]

  def apply(request: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    Marshal(value).toResponseFor(request)
}

object ToResponseMarshallable {
  implicit def apply[A](_value: A)(implicit _marshaller: ToResponseMarshaller[A]): ToResponseMarshallable =
    new ToResponseMarshallable {
      type T = A
      def value: T = _value
      def marshaller: ToResponseMarshaller[T] = _marshaller
    }

  implicit val marshaller: ToResponseMarshaller[ToResponseMarshallable] =
    Marshaller { implicit ec ⇒ marshallable ⇒ marshallable.marshaller(marshallable.value) }
}