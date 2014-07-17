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

import akka.http.marshalling.ToResponseMarshaller

import scala.collection.GenTraversableOnce
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import akka.actor.{ Status, ActorRef }
//import spray.httpx.marshalling._
import akka.http.model._
import StatusCodes._
import headers._
import MediaTypes._

sealed trait RouteResult
case class CompleteWith(response: HttpResponse) extends RouteResult
case class RouteException(exception: Throwable) extends RouteResult
case class Rejected(rejections: List[Rejection]) extends RouteResult {
  def map(f: Rejection ⇒ Rejection) = Rejected(rejections.map(f))
  def flatMap(f: Rejection ⇒ GenTraversableOnce[Rejection]) = Rejected(rejections.flatMap(f))
}
case class DeferredResult(result: Future[RouteResult]) extends RouteResult

/**
 * Immutable object encapsulating the context of an [[spray.http.HttpRequest]]
 * as it flows through a ''spray'' Route structure.
 */
trait RequestContext {
  val request: HttpRequest
  val unmatchedPath: Uri.Path

  /**
   * Returns a copy of this context with the HttpRequest transformed by the given function.
   */
  def withRequestMapped(f: HttpRequest ⇒ HttpRequest): RequestContext

  /**
   * Returns a copy of this context with the unmatchedPath transformed by the given function.
   */
  def withUnmatchedPathMapped(f: Uri.Path ⇒ Uri.Path): RequestContext

  /**
   * Returns a copy of this context with the given function handling a part of the response space.
   */
  def withRouteResponseHandling(f: PartialFunction[RouteResult, RouteResult]): RequestContext

  /**
   * Returns a copy of this context with the given response handling function chained into the response chain.
   */
  def withRouteResponseRouting(f: PartialFunction[RouteResult, Route]): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withRouteResponseMapped(f: RouteResult ⇒ RouteResult): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withRouteResponseMappedPF(f: PartialFunction[RouteResult, RouteResult]): RequestContext

  /**
   * Returns a copy of this context with the given rejection handling function chained into the response chain.
   */
  def withRejectionHandling(f: List[Rejection] ⇒ RouteResult): RequestContext

  /**
   * Returns a copy of this context with the given rejection transformation function chained into the response chain.
   */
  def withRejectionsMapped(f: List[Rejection] ⇒ List[Rejection]): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withHttpResponseMapped(f: HttpResponse ⇒ HttpResponse): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withHttpResponseEntityMapped(f: HttpEntity ⇒ HttpEntity): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withHttpResponseHeadersMapped(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestContext

  /**
   * Removes a potentially existing Accept header from the request headers.
   */
  def withContentNegotiationDisabled: RequestContext

  def withExceptionHandling(handler: ExceptionHandler): RequestContext

  def withUnmatchedPath(path: Uri.Path): RequestContext

  /**
   * Rejects the request with the given rejections.
   */
  def reject(rejection: Rejection): RouteResult

  /**
   * Rejects the request with the given rejections.
   */
  def reject(rejections: Rejection*): RouteResult

  /**
   * Completes the request with redirection response of the given type to the given URI.
   */
  def redirect(uri: Uri, redirectionType: Redirection): RouteResult

  /**
   * Completes the request with a response created by marshalling the given object using
   * the in-scope marshaller for the type.
   */
  def complete[T](obj: T)(implicit marshaller: ToResponseMarshaller[T]): RouteResult

  def deferHandling(future: Future[RouteResult])(implicit ec: ExecutionContext): RouteResult

  /**
   * Bubbles the given error up the response chain where it is dealt with by the closest `handleExceptions`
   * directive and its ``ExceptionHandler``, unless the error is a ``RejectionError``. In this case the
   * wrapped rejection is unpacked and "executed".
   */
  def failWith(error: Throwable): RouteResult
}
