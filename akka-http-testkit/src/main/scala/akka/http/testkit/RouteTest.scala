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

package akka.http.testkit

import akka.http.routing.impl.RequestContextImpl
import akka.stream.{ MaterializerSettings, FlowMaterializer }

import scala.collection.immutable

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import org.scalatest.Suite
import akka.actor.ActorSystem
import akka.http.routing.directives.ExecutionDirectives
import akka.http.routing.{ RouteResult ⇒ RoutingRouteResult, _ }
import akka.http.unmarshalling._
import akka.http.model._
import akka.http.util._
import HttpEntity.ChunkStreamPart
import akka.http.model.headers.Host

trait RouteTest extends RequestBuilding with RouteResultComponent {
  this: TestFrameworkInterface ⇒

  /** Override to supply a custom ActorSystem */
  protected def createActorSystem(): ActorSystem =
    ActorSystem(actorSystemNameFrom(getClass), testConfig)

  def actorSystemNameFrom(clazz: Class[_]) =
    clazz.getName
      .replace('.', '-')
      .replace('_', '-')
      .filter(_ != '$')

  def testConfigSource: String = ""
  def testConfig: Config = {
    val source = testConfigSource
    val config = if (source.isEmpty) ConfigFactory.empty() else ConfigFactory.parseString(source)
    config.withFallback(ConfigFactory.load())
  }
  implicit val system = createActorSystem()
  implicit def executor = system.dispatcher
  implicit val materializer = FlowMaterializer(MaterializerSettings(), Some(actorSystemNameFrom(getClass) + "flow"))

  def cleanUp(): Unit = { system.shutdown() }

  private val dynRR = new DynamicVariable[RouteResult](null)

  private def assertInCheck(): Unit = {
    if (dynRR.value == null) sys.error("This value is only available inside of a `check` construct!")
  }

  def check[T](body: ⇒ T): RouteResult ⇒ T = result ⇒ dynRR.withValue(result.awaitResult)(body)

  private def result = { assertInCheck(); dynRR.value }
  def handled: Boolean = result.handled
  def response: HttpResponse = result.response
  def entity: HttpEntity = response.entity
  @deprecated("Use `responseAs` instead.", "1.0/1.1/1.2-RC1")
  def entityAs[T: Unmarshaller: ClassTag]: T =
    Await.result(
      entity.as[T]
        .recoverWith {
          case error ⇒ failTest(s"Could not unmarshal response to type '${implicitly[ClassTag[T]]}' for `entityAs` assertion: $error\n\nResponse was: $response")
        }, 1.second)
  def responseAs[T: FromResponseUnmarshaller: ClassTag]: T =
    Await.result(
      response.as[T].recoverWith {
        case error ⇒ failTest("Could not unmarshal response " +
          s"to type '${implicitly[ClassTag[T]]}' for `responseAs` assertion: $error\n\nResponse was: $response")
      }, 1.second)

  def body: HttpEntity = entity // FIXME: .toOption getOrElse failTest("Response has no body")
  def contentType: ContentType = body.contentType
  def mediaType: MediaType = contentType.mediaType
  def charset: HttpCharset = contentType.charset
  def definedCharset: Option[HttpCharset] = contentType.definedCharset
  def headers: immutable.Seq[HttpHeader] = response.headers
  def header[T <: HttpHeader: ClassTag]: Option[T] = response.header[T]
  def header(name: String): Option[HttpHeader] = response.headers.find(_.is(name.toLowerCase))
  def status: StatusCode = response.status
  def chunks: immutable.Seq[ChunkStreamPart] = result.chunks
  def closingExtension: String = result.closingExtension
  def trailer: List[HttpHeader] = result.trailer
  def rejections: List[Rejection] = RejectionHandler.applyTransformations(result.rejections)
  def rejection: Rejection = {
    val r = rejections
    if (r.size == 1) r.head else failTest("Expected a single rejection but got %s (%s)".format(r.size, r))
  }

  /**
   * A dummy that can be used as `~> runRoute` to run the route but without blocking for the result.
   * The result of the pipeline is the result that can later be checked with `check`. See the
   * "separate running route from checking" example from ScalatestRouteTestSpec.scala.
   */
  def runRoute: RouteResult ⇒ RouteResult = identity

  // there is already an implicit class WithTransformation in scope (inherited from spray.httpx.TransformerPipelineSupport)
  // however, this one takes precedence
  implicit class WithTransformation2(request: HttpRequest) {
    def ~>[A, B](f: A ⇒ B)(implicit ta: TildeArrow[A, B]): ta.Out = ta(request, f)
  }

  abstract class TildeArrow[A, B] {
    type Out
    def apply(request: HttpRequest, f: A ⇒ B): Out
  }

  case class DefaultHostInfo(host: Host, securedConnection: Boolean)
  object DefaultHostInfo {
    implicit def defaultHost: DefaultHostInfo =
      DefaultHostInfo(Host("example.com"), securedConnection = false)
  }
  object TildeArrow {
    implicit object InjectIntoRequestTransformer extends TildeArrow[HttpRequest, HttpRequest] {
      type Out = HttpRequest
      def apply(request: HttpRequest, f: HttpRequest ⇒ HttpRequest) = f(request)
    }
    implicit def injectIntoRoute(implicit timeout: RouteTestTimeout, settings: RoutingSettings,
                                 log: LoggingContext, eh: ExceptionHandler, defaultHostInfo: DefaultHostInfo) =
      new TildeArrow[RequestContext, RoutingRouteResult] {
        type Out = RouteResult

        def apply(request: HttpRequest, route: Route): Out = {
          val routeResult = new RouteResult(timeout.duration)
          val effectiveRequest =
            request.withEffectiveUri(
              securedConnection = defaultHostInfo.securedConnection,
              defaultHostHeader = defaultHostInfo.host)
          val res =
            ExecutionDirectives.handleExceptions(eh orElse ExceptionHandler.default)(route) {
              new RequestContextImpl(
                request = effectiveRequest,
                unmatchedPath = effectiveRequest.uri.path)
            }
          routeResult.handleResult(res)
          routeResult
        }
        /*{
          val routeResult = new RouteResult(timeout.duration)
          val effectiveRequest =
            request.withEffectiveUri(
              securedConnection = defaultHostInfo.securedConnection,
              defaultHostHeader = defaultHostInfo.host)
          ExecutionDirectives.handleExceptions(eh orElse ExceptionHandler.default)(route) {
            RequestContext(
              request = effectiveRequest,
              responder = routeResult.handler,
              unmatchedPath = effectiveRequest.uri.path)
          }
          routeResult
        }*/
      }
  }
}

trait ScalatestRouteTest extends RouteTest with ScalatestInterface { this: Suite ⇒ }

//FIXME: trait Specs2RouteTest extends RouteTest with Specs2Interface
