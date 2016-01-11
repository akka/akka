/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit

import com.typesafe.config.{ ConfigFactory, Config }
import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import org.scalatest.Suite
import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import akka.http.client.RequestBuilding
import akka.http.util.FastFuture
import akka.http.server._
import akka.http.unmarshalling._
import akka.http.model._
import headers.Host
import FastFuture._

trait RouteTest extends RequestBuilding with RouteTestResultComponent {
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
  implicit val materializer = FlowMaterializer()

  def cleanUp(): Unit = system.shutdown()

  private val dynRR = new DynamicVariable[RouteTestResult](null)
  private def result =
    if (dynRR.value ne null) dynRR.value
    else sys.error("This value is only available inside of a `check` construct!")

  def check[T](body: ⇒ T): RouteTestResult ⇒ T = result ⇒ dynRR.withValue(result.awaitResult)(body)

  def handled: Boolean = result.handled
  def response: HttpResponse = result.response
  def entity: HttpEntity = result.entity
  def chunks: immutable.Seq[HttpEntity.ChunkStreamPart] = result.chunks
  def entityAs[T: FromEntityUnmarshaller: ClassTag](implicit timeout: Duration = 1.second): T = {
    def msg(e: Throwable) = s"Could not unmarshal entity to type '${implicitly[ClassTag[T]]}' for `entityAs` assertion: $e\n\nResponse was: $response"
    Await.result(Unmarshal(entity).to[T].fast.recover[T] { case error ⇒ failTest(msg(error)) }, timeout)
  }
  def responseAs[T: FromResponseUnmarshaller: ClassTag](implicit timeout: Duration = 1.second): T = {
    def msg(e: Throwable) = s"Could not unmarshal response to type '${implicitly[ClassTag[T]]}' for `responseAs` assertion: $e\n\nResponse was: $response"
    Await.result(Unmarshal(response).to[T].fast.recover[T] { case error ⇒ failTest(msg(error)) }, timeout)
  }
  def contentType: ContentType = entity.contentType
  def mediaType: MediaType = contentType.mediaType
  def charset: HttpCharset = contentType.charset
  def definedCharset: Option[HttpCharset] = contentType.definedCharset
  def headers: immutable.Seq[HttpHeader] = response.headers
  def header[T <: HttpHeader: ClassTag]: Option[T] = response.header[T]
  def header(name: String): Option[HttpHeader] = response.headers.find(_.is(name.toLowerCase))
  def status: StatusCode = response.status

  def closingExtension: String = chunks.lastOption match {
    case Some(HttpEntity.LastChunk(extension, _)) ⇒ extension
    case _                                        ⇒ ""
  }
  def trailer: immutable.Seq[HttpHeader] = chunks.lastOption match {
    case Some(HttpEntity.LastChunk(_, trailer)) ⇒ trailer
    case _                                      ⇒ Nil
  }

  def rejections: List[Rejection] = result.rejections
  def rejection: Rejection = {
    val r = rejections
    if (r.size == 1) r.head else failTest("Expected a single rejection but got %s (%s)".format(r.size, r))
  }

  /**
   * A dummy that can be used as `~> runRoute` to run the route but without blocking for the result.
   * The result of the pipeline is the result that can later be checked with `check`. See the
   * "separate running route from checking" example from ScalatestRouteTestSpec.scala.
   */
  def runRoute: RouteTestResult ⇒ RouteTestResult = akka.http.util.identityFunc

  // there is already an implicit class WithTransformation in scope (inherited from akka.http.testkit.TransformerPipelineSupport)
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
    implicit def defaultHost: DefaultHostInfo = DefaultHostInfo(Host("example.com"), securedConnection = false)
  }
  object TildeArrow {
    implicit object InjectIntoRequestTransformer extends TildeArrow[HttpRequest, HttpRequest] {
      type Out = HttpRequest
      def apply(request: HttpRequest, f: HttpRequest ⇒ HttpRequest) = f(request)
    }
    implicit def injectIntoRoute(implicit timeout: RouteTestTimeout, setup: RoutingSetup,
                                 defaultHostInfo: DefaultHostInfo) =
      new TildeArrow[RequestContext, Future[RouteResult]] {
        type Out = RouteTestResult
        def apply(request: HttpRequest, route: Route): Out = {
          val routeTestResult = new RouteTestResult(timeout.duration)(setup.materializer)
          val effectiveRequest =
            request.withEffectiveUri(
              securedConnection = defaultHostInfo.securedConnection,
              defaultHostHeader = defaultHostInfo.host)
          val ctx = new RequestContextImpl(effectiveRequest, setup.routingLog.requestLog(effectiveRequest))
          val sealedExceptionHandler = {
            import setup._
            if (exceptionHandler.isDefault) exceptionHandler
            else exceptionHandler orElse ExceptionHandler.default(settings)(setup.executionContext)
          }
          val semiSealedRoute = // sealed for exceptions but not for rejections
            Directives.handleExceptions(sealedExceptionHandler) { route }
          val deferrableRouteResult = semiSealedRoute(ctx)
          deferrableRouteResult.fast.foreach(routeTestResult.handleResult)(setup.executor)
          routeTestResult
        }
      }
  }
}

trait ScalatestRouteTest extends RouteTest with TestFrameworkInterface.Scalatest { this: Suite ⇒ }

//FIXME: trait Specs2RouteTest extends RouteTest with Specs2Interface