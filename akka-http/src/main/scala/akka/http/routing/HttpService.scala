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

import akka.shapeless.HList

import scala.collection.immutable
import scala.concurrent.Future

import akka.http.model.{ HttpRequest, HttpResponse, Uri }

object NewTypes {
  trait HttpService {
    def handle(request: HttpRequest): Future[HttpResponse]
  }

  sealed trait RouteResult
  case class Complete(response: HttpResponse) extends RouteResult
  case class Reject(rejections: immutable.Seq[Rejection]) extends RouteResult

  case class RequestContext(request: HttpRequest, unmatchedPath: Uri.Path)

  trait Route {
    def apply(ctx: RequestContext): Future[RouteResult]
  }

  type Directive[L <: HList] = L ⇒ Route
}

/*
import scala.util.control.NonFatal
import akka.actor._
import akka.io.Tcp
import spray.util.LoggingContext
import akka.http.model._
import StatusCodes._

trait HttpServiceBase extends Directives {

  /**
   * Supplies the actor behavior for executing the given route.
   */
  def runRoute(route: Route)(implicit eh: ExceptionHandler, rh: RejectionHandler, ac: ActorContext,
                             rs: RoutingSettings, log: LoggingContext): Actor.Receive = {
    val sealedExceptionHandler = eh orElse ExceptionHandler.default
    val sealedRoute = sealRoute(route)(sealedExceptionHandler, rh)
    def runSealedRoute(ctx: RequestContext): Unit =
      try sealedRoute(ctx)
      catch {
        case NonFatal(e) ⇒
          val errorRoute = sealedExceptionHandler(e)
          errorRoute(ctx)
      }

    {
      case request: HttpRequest ⇒
        val ctx = RequestContext(request, ac.sender(), request.uri.path).withDefaultSender(ac.self)
        runSealedRoute(ctx)

      case ctx: RequestContext ⇒ runSealedRoute(ctx)

      case Tcp.Connected(_, _) ⇒
        // by default we register ourselves as the handler for a new connection
        ac.sender() ! Tcp.Register(ac.self)

      case x: Tcp.ConnectionClosed        ⇒ onConnectionClosed(x)

      case Timedout(request: HttpRequest) ⇒ runRoute(timeoutRoute)(eh, rh, ac, rs, log)(request)
    }
  }

  /**
   * Called by the `runRoute` behavior when a `ConnectionClosed` event is received.
   * Override with custom logic if required (by default the method does nothing).
   */
  def onConnectionClosed(ev: Tcp.ConnectionClosed): Unit = ()

  /**
   * "Seals" a route by wrapping it with exception handling and rejection conversion.
   */
  def sealRoute(route: Route)(implicit eh: ExceptionHandler, rh: RejectionHandler): Route =
    (handleExceptions(eh) & handleRejections(sealRejectionHandler(rh)))(route)

  def sealRejectionHandler(rh: RejectionHandler): RejectionHandler =
    rh orElse RejectionHandler.Default orElse handleUnhandledRejections

  def handleUnhandledRejections: RejectionHandler.PF = {
    case x :: _ ⇒ sys.error("Unhandled rejection: " + x)
  }

  //# timeout-route
  def timeoutRoute: Route = complete(
    InternalServerError,
    "The server was not able to produce a timely response to your request.")
  //#
}

object HttpService extends HttpServiceBase

trait HttpService extends HttpServiceBase {
  /**
   * An ActorRefFactory needs to be supplied by the class mixing us in
   * (mostly either the service actor or the service test)
   */
  implicit def actorRefFactory: ActorRefFactory
}

abstract class HttpServiceActor extends Actor with HttpService {
  def actorRefFactory = context
}
*/
