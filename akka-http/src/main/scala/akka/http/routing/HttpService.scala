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

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

import scala.concurrent.{ ExecutionContext, Future }

import akka.http.Http

import akka.http.model.{ HttpRequest, HttpResponse }

import scala.util.control.NonFatal

object HttpService {
  def handleConnections(serverConn: Future[Http.ServerBinding], materializer: FlowMaterializer)(route: Route)(implicit eh: ExceptionHandler, rh: RejectionHandler, rs: RoutingSettings, ec: ExecutionContext): Unit =
    runServerWithHandler(serverConn, materializer) { request ⇒
      def handleResult(result: RouteResult): Future[HttpResponse] = result match {
        case CompleteWith(response)     ⇒ Future.successful(response)
        case DeferredResult(lateResult) ⇒ lateResult.flatMap(handleResult)
        case _                          ⇒ throw new IllegalStateException("Sealed route shouldn't generate anything else than completed results")
      }
      import Directives._
      def sealRoute(route: Route): Route =
        (handleExceptions(eh) & handleRejections(sealRejectionHandler(rh)))(route)
      def sealRejectionHandler(rh: RejectionHandler): RejectionHandler =
        rh orElse RejectionHandler.Default orElse handleUnhandledRejections

      val sealedExceptionHandler = eh orElse ExceptionHandler.default
      val sealedRoute = sealRoute(route)
      val ctx = impl.RequestContextImpl(request, request.uri.path)
      val result =
        try sealedRoute(ctx)
        catch {
          case NonFatal(e) ⇒
            val errorRoute = sealedExceptionHandler(e)
            errorRoute(ctx)
        }
      handleResult(result)
    }

  def handleUnhandledRejections: RejectionHandler.PF = {
    case x :: _ ⇒ sys.error("Unhandled rejection: " + x)
  }

  def runServerWithHandler(serverConn: Future[Http.ServerBinding], materializer: FlowMaterializer)(requestHandler: HttpRequest ⇒ Future[HttpResponse])(implicit ec: ExecutionContext): Unit =
    serverConn.foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            println("Accepted new connection from " + remoteAddress)
            Flow(requestProducer).mapFuture(requestHandler).produceTo(materializer, responseConsumer)
        }.consume(materializer)
    }
}
