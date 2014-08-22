/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing
package directives

import akka.shapeless._
import akka.http.marshalling._
import akka.http.unmarshalling._

import scala.concurrent.ExecutionContext

trait MarshallingDirectives {
  import BasicDirectives._
  import MiscDirectives._
  import RouteDirectives._

  /**
   * Unmarshalls the requests entity to the given type passes it to its inner Route.
   * If there is a problem with unmarshalling the request is rejected with the [[spray.routing.Rejection]]
   * produced by the unmarshaller.
   */
  def entity[T](um: FromRequestUnmarshaller[T]): Directive1[T] = FIXME
  /*extract(_.request.as(um)).flatMap[T :: HNil] {
      case Right(value)                            ⇒ provide(value)
      case Left(ContentExpected)                   ⇒ reject(RequestEntityExpectedRejection)
      case Left(UnsupportedContentType(supported)) ⇒ reject(UnsupportedRequestContentTypeRejection(supported))
      case Left(MalformedContent(errorMsg, cause)) ⇒ reject(MalformedRequestContentRejection(errorMsg, cause))
    } & cancelAllRejections(ofTypes(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection]))*/

  /**
   * Returns the in-scope FromRequestUnmarshaller for the given type.
   */
  def as[T](implicit um: FromRequestUnmarshaller[T]) = um

  /**
   * Uses the marshaller for the given type to produce a completion function that is passed to its inner route.
   * You can use it do decouple marshaller resolution from request completion.
   */
  def produce[T](magnet: ProduceMagnet[T]): Directive[(T ⇒ RouteResult) :: HNil] =
    extract { ctx ⇒ (value: T) ⇒ ctx.complete(ToResponseMarshallable.isMarshallable(value)(magnet.marshaller, magnet.ec)) } & cancelAllRejections(ofType[UnacceptedResponseContentTypeRejection])

  /**
   * Returns the in-scope Marshaller for the given type.
   */
  def instanceOf[T](implicit m: ToResponseMarshaller[T]) = m

  /**
   * Completes the request using the given function. The input to the function is produced with the in-scope
   * entity unmarshaller and the result value of the function is marshalled with the in-scope marshaller.
   */
  def handleWith[A, B](f: A ⇒ B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B], ec: ExecutionContext): Route =
    entity(um) { a ⇒ RouteDirectives.complete(f(a)) }
}

object MarshallingDirectives extends MarshallingDirectives

case class ProduceMagnet[T](marshaller: ToResponseMarshaller[T], ec: ExecutionContext)
object ProduceMagnet {
  implicit def create[T](marshaller: ToResponseMarshaller[T])(implicit ec: ExecutionContext): ProduceMagnet[T] =
    ProduceMagnet[T](marshaller, ec)
}