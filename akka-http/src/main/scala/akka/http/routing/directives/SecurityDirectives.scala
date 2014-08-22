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

import scala.concurrent.{ ExecutionContext, Future }
import akka.shapeless.HNil
import authentication._
import BasicDirectives._
import FutureDirectives._
import MiscDirectives._
import RouteDirectives._

trait SecurityDirectives {

  /**
   * Wraps its inner Route with authentication support.
   * Can be called either with a ``Future[Authentication[T]]`` or ``ContextAuthenticator[T]``.
   */
  def authenticate[T](magnet: AuthMagnet[T]): Directive1[T] = magnet.directive

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[spray.AuthorizationFailedRejection]].
   */
  def authorize(check: ⇒ Boolean): Directive0 = authorize(_ ⇒ check)

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[spray.AuthorizationFailedRejection]].
   */
  def authorize(check: RequestContext ⇒ Boolean): Directive0 =
    extract(check).flatMap[HNil](if (_) pass else reject(AuthorizationFailedRejection)) &
      cancelRejection(AuthorizationFailedRejection)
}

class AuthMagnet[T](authDirective: Directive1[Authentication[T]])(implicit executor: ExecutionContext) {
  val directive: Directive1[T] = authDirective.flatMap {
    case Right(user)     ⇒ provide(user)
    case Left(rejection) ⇒ reject(rejection)
  }
}

object AuthMagnet {
  implicit def fromFutureAuth[T](auth: ⇒ Future[Authentication[T]])(implicit executor: ExecutionContext): AuthMagnet[T] =
    new AuthMagnet(onSuccess(auth))

  implicit def fromContextAuthenticator[T](auth: ContextAuthenticator[T])(implicit executor: ExecutionContext): AuthMagnet[T] =
    new AuthMagnet(extract(auth).flatMap(onSuccess(_)))
}
