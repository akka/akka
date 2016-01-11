/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.concurrent.Promise
import scala.util.{ Failure, Success }
import akka.http.marshalling.ToResponseMarshaller
import akka.http.unmarshalling.{ Unmarshaller, FromRequestUnmarshaller }
import akka.http.util._

trait MarshallingDirectives {
  import BasicDirectives._
  import FutureDirectives._
  import RouteDirectives._

  /**
   * Unmarshalls the requests entity to the given type passes it to its inner Route.
   * If there is a problem with unmarshalling the request is rejected with the [[Rejection]]
   * produced by the unmarshaller.
   */
  def entity[T](um: FromRequestUnmarshaller[T]): Directive1[T] =
    extractRequest.flatMap[Tuple1[T]] { request ⇒
      onComplete(um(request)) flatMap {
        case Success(value) ⇒ provide(value)
        case Failure(Unmarshaller.NoContentException) ⇒ reject(RequestEntityExpectedRejection)
        case Failure(Unmarshaller.UnsupportedContentTypeException(x)) ⇒ reject(UnsupportedRequestContentTypeRejection(x))
        case Failure(x) ⇒ reject(MalformedRequestContentRejection(x.getMessage.nullAsEmpty, Option(x.getCause)))
      }
    } & cancelRejections(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection])

  /**
   * Returns the in-scope [[FromRequestUnmarshaller]] for the given type.
   */
  def as[T](implicit um: FromRequestUnmarshaller[T]) = um

  /**
   * Uses the marshaller for the given type to produce a completion function that is passed to its inner function.
   * You can use it do decouple marshaller resolution from request completion.
   */
  def completeWith[T](marshaller: ToResponseMarshaller[T])(inner: (T ⇒ Unit) ⇒ Unit): Route = { ctx ⇒
    import ctx.executionContext
    implicit val m = marshaller
    val promise = Promise[T]()
    inner(promise.success(_))
    ctx.complete(promise.future)
  }

  /**
   * Returns the in-scope Marshaller for the given type.
   */
  def instanceOf[T](implicit m: ToResponseMarshaller[T]): ToResponseMarshaller[T] = m

  /**
   * Completes the request using the given function. The input to the function is produced with the in-scope
   * entity unmarshaller and the result value of the function is marshalled with the in-scope marshaller.
   */
  def handleWith[A, B](f: A ⇒ B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    entity(um) { a ⇒ complete(f(a)) }
}

object MarshallingDirectives extends MarshallingDirectives
