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

package akka.http.routing.directives

import scala.concurrent.Future
import akka.event.NoLogging
import akka.http.routing._
import authentication._
import akka.http.model._
import headers._
import AuthenticationFailedRejection._

class SecurityDirectivesSpec extends RoutingSpec {

  val dontAuth = BasicAuth(UserPassAuthenticator[BasicUserContext](_ ⇒ Future.successful(None)), "Realm")
  val challenge = `WWW-Authenticate`(HttpChallenge("Basic", "Realm"))

  val doAuth = BasicAuth(UserPassAuthenticator[BasicUserContext] { userPassOption ⇒
    Future.successful(Some(BasicUserContext(userPassOption.get.user)))
  }, "Realm")

  "the 'authenticate(BasicAuth())' directive" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        authenticate(dontAuth) { echoComplete }
      } ~> check { rejection mustEqual AuthenticationFailedRejection(CredentialsMissing, List(challenge)) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(BasicHttpCredentials("Bob", "")) ~> {
        authenticate(dontAuth) { echoComplete }
      } ~> check { rejection mustEqual AuthenticationFailedRejection(CredentialsRejected, List(challenge)) }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> handleRejections(RejectionHandler.Default) {
        authenticate(dontAuth) { echoComplete }
      } ~> check {
        status mustEqual StatusCodes.Unauthorized
        responseAs[String] mustEqual "The resource requires authentication, which was not supplied with the request"
      }
    }
    "extract the object representing the user identity created by successful authentication" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        authenticate(doAuth) { echoComplete }
      } ~> check { responseAs[String] mustEqual "BasicUserContext(Alice)" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        handleExceptions(ExceptionHandler.default) {
          authenticate(doAuth) { _ ⇒ throw TestException }
        }
      } ~> check { status mustEqual StatusCodes.InternalServerError }
    }
  }

  "the 'authenticate(<ContextAuthenticator>)' directive" should {
    case object AuthenticationRejection extends Rejection

    val myAuthenticator: ContextAuthenticator[Int] = ctx ⇒ Future {
      Either.cond(ctx.request.uri.authority.host == Uri.NamedHost("spray.io"), 42, AuthenticationRejection)
    }
    "reject requests not satisfying the filter condition" in {
      Get() ~> authenticate(myAuthenticator) { echoComplete } ~>
        check { rejection mustEqual AuthenticationRejection }
    }
    "pass on the authenticator extraction if the filter conditions is met" in {
      Get() ~> Host("spray.io") ~> authenticate(myAuthenticator) { echoComplete } ~>
        check { responseAs[String] mustEqual "42" }
    }
  }

  "the 'authenticate(<Future>)' directive" should {
    case object AuthenticationRejection extends Rejection

    var i = 0
    def nextInt() = { i += 1; i }
    def myAuthenticator: Future[Authentication[Int]] = Future.successful(Right(nextInt()))

    val route = authenticate(myAuthenticator) { echoComplete }

    "pass on the authenticator extraction if the filter conditions is met" in {
      Get() ~> Host("spray.io") ~> route ~>
        check { responseAs[String] mustEqual "1" }
      Get() ~> Host("spray.io") ~> route ~>
        check { responseAs[String] mustEqual "2" }
    }
  }
}
