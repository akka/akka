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
package authentication

import com.typesafe.config.Config
import scala.concurrent.{ ExecutionContext, Future }
import akka.http.model._
import headers._
import AuthenticationFailedRejection._

/**
 * An HttpAuthenticator is a ContextAuthenticator that uses credentials passed to the server via the
 * HTTP `Authorization` header to authenticate the user and extract a user object.
 */
trait HttpAuthenticator[U] extends ContextAuthenticator[U] {

  def apply(ctx: RequestContext) = {
    val authHeader = ctx.request.header[`Authorization`]
    val credentials = authHeader.map { case Authorization(creds) ⇒ creds }
    authenticate(credentials, ctx) map {
      case Some(userContext) ⇒ Right(userContext)
      case None ⇒
        val cause = if (authHeader.isEmpty) CredentialsMissing else CredentialsRejected
        Left(AuthenticationFailedRejection(cause, getChallengeHeaders(ctx.request)))
    }
  }

  implicit def executionContext: ExecutionContext

  def authenticate(credentials: Option[HttpCredentials], ctx: RequestContext): Future[Option[U]]

  def getChallengeHeaders(httpRequest: HttpRequest): List[HttpHeader]
}

/**
 * The BasicHttpAuthenticator implements HTTP Basic Auth.
 */
class BasicHttpAuthenticator[U](val realm: String, val userPassAuthenticator: UserPassAuthenticator[U])(implicit val executionContext: ExecutionContext)
  extends HttpAuthenticator[U] {

  def authenticate(credentials: Option[HttpCredentials], ctx: RequestContext) = {
    userPassAuthenticator {
      credentials.flatMap {
        case BasicHttpCredentials(user, pass) ⇒ Some(UserPass(user, pass))
        case _                                ⇒ None
      }
    }
  }

  def getChallengeHeaders(httpRequest: HttpRequest) =
    `WWW-Authenticate`(HttpChallenge(scheme = "Basic", realm = realm, params = Map.empty)) :: Nil

}

object BasicAuth {
  def apply()(implicit settings: RoutingSettings, ec: ExecutionContext): BasicHttpAuthenticator[BasicUserContext] =
    apply("Secured Resource")

  def apply(realm: String)(implicit settings: RoutingSettings,
                           ec: ExecutionContext): BasicHttpAuthenticator[BasicUserContext] =
    apply(realm, userPass ⇒ BasicUserContext(userPass.user))

  def apply[T](realm: String, createUser: UserPass ⇒ T)(implicit settings: RoutingSettings, ec: ExecutionContext): BasicHttpAuthenticator[T] =
    apply(realm, settings.users, createUser)

  def apply[T](realm: String, config: Config, createUser: UserPass ⇒ T)(implicit ec: ExecutionContext): BasicHttpAuthenticator[T] =
    apply(UserPassAuthenticator.fromConfig(config)(createUser), realm)

  def apply[T](authenticator: UserPassAuthenticator[T], realm: String)(implicit ec: ExecutionContext): BasicHttpAuthenticator[T] =
    new BasicHttpAuthenticator[T](realm, authenticator)
}