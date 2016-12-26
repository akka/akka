/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Server, RawHeader }
import akka.http.scaladsl.server.RouteResult.{ Complete, Rejected }
import akka.http.scaladsl.server._
import akka.http.scaladsl.settings.RoutingSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ FileIO, Sink, Source }
import akka.util.ByteString
import docs.http.scaladsl.server.RoutingSpec

import scala.concurrent.Future
import scala.util.control.NonFatal

class BasicDirectivesExamplesSpec extends RoutingSpec {
  "0extract" in {
    //#extract0
    val uriLength = extract(_.request.uri.toString.length)
    val route =
      uriLength { len =>
        complete(s"The length of the request URI is $len")
      }

    // tests:
    Get("/abcdef") ~> route ~> check {
      responseAs[String] shouldEqual "The length of the request URI is 25"
    }
    //#extract0
  }
  "0extractLog" in {
    //#extract0Log
    val route =
      extractLog { log =>
        log.debug("I'm logging things in much detail..!")
        complete("It's amazing!")
      }

    // tests:
    Get("/abcdef") ~> route ~> check {
      responseAs[String] shouldEqual "It's amazing!"
    }
    //#extract0Log
  }
  "withMaterializer-0" in {
    //#withMaterializer-0
    val special = ActorMaterializer(namePrefix = Some("special"))

    def sample() =
      path("sample") {
        extractMaterializer { mat =>
          complete {
            // explicitly use the materializer:
            Source.single(s"Materialized by ${mat.##}!")
              .runWith(Sink.head)(mat)
          }
        }
      }

    val route =
      pathPrefix("special") {
        withMaterializer(special) {
          sample() // `special` materializer will be used
        }
      } ~ sample() // default materializer will be used

    // tests:
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Materialized by ${materializer.##}!"
    }
    Get("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Materialized by ${special.##}!"
    }
    //#withMaterializer-0
  }
  "extractMaterializer-0" in {
    //#extractMaterializer-0
    val route =
      path("sample") {
        extractMaterializer { materializer =>
          complete {
            // explicitly use the `materializer`:
            Source.single(s"Materialized by ${materializer.##}!")
              .runWith(Sink.head)(materializer)
          }
        }
      } // default materializer will be used

    // tests:
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Materialized by ${materializer.##}!"
    }
    //#extractMaterializer-0
  }
  "withExecutionContext-0" in compileOnlySpec {
    //#withExecutionContext-0
    val special = system.dispatchers.lookup("special")

    def sample() =
      path("sample") {
        extractExecutionContext { implicit executor =>
          complete {
            Future(s"Run on ${executor.##}!") // uses the `executor` ExecutionContext
          }
        }
      }

    val route =
      pathPrefix("special") {
        withExecutionContext(special) {
          sample() // `special` execution context will be used
        }
      } ~ sample() // default execution context will be used

    // tests:
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Run on ${system.dispatcher.##}!"
    }
    Get("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Run on ${special.##}!"
    }
    //#withExecutionContext-0
  }
  "extractExecutionContext-0" in compileOnlySpec {
    //#extractExecutionContext-0
    def sample() =
      path("sample") {
        extractExecutionContext { implicit executor =>
          complete {
            Future(s"Run on ${executor.##}!") // uses the `executor` ExecutionContext
          }
        }
      }

    val route =
      pathPrefix("special") {
        sample() // default execution context will be used
      }

    // tests:
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Run on ${system.dispatcher.##}!"
    }
    //#extractExecutionContext-0
  }
  "0withLog" in {
    //#withLog0
    val special = Logging(system, "SpecialRoutes")

    def sample() =
      path("sample") {
        extractLog { implicit log =>
          complete {
            val msg = s"Logging using $log!"
            log.debug(msg)
            msg
          }
        }
      }

    val route =
      pathPrefix("special") {
        withLog(special) {
          sample() // `special` logging adapter will be used
        }
      } ~ sample() // default logging adapter will be used

    // tests:
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Logging using ${system.log}!"
    }
    Get("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Logging using $special!"
    }
    //#withLog0
  }
  "withSettings-0" in compileOnlySpec {
    //#withSettings-0
    val special = RoutingSettings(system).withFileIODispatcher("special-io-dispatcher")

    def sample() =
      path("sample") {
        complete {
          // internally uses the configured fileIODispatcher:
          val source = FileIO.fromPath(Paths.get("example.json"))
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, source))
        }
      }

    val route =
      get {
        pathPrefix("special") {
          withSettings(special) {
            sample() // `special` file-io-dispatcher will be used to read the file
          }
        } ~ sample() // default file-io-dispatcher will be used to read the file
      }

    // tests:
    Post("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"{}"
    }
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual "{}"
    }
    //#withSettings-0
  }
  "textract" in {
    //#textract
    val pathAndQuery = textract { ctx =>
      val uri = ctx.request.uri
      (uri.path, uri.query())
    }
    val route =
      pathAndQuery { (p, query) =>
        complete(s"The path is $p and the query is $query")
      }

    // tests:
    Get("/abcdef?ghi=12") ~> route ~> check {
      responseAs[String] shouldEqual "The path is /abcdef and the query is ghi=12"
    }
    //#textract
  }
  "tprovide" in {
    //#tprovide
    def provideStringAndLength(value: String) = tprovide((value, value.length))
    val route =
      provideStringAndLength("test") { (value, len) =>
        complete(s"Value is $value and its length is $len")
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Value is test and its length is 4"
    }
    //#tprovide
  }
  "0mapResponse" in {
    //#mapResponse0
    def overwriteResultStatus(response: HttpResponse): HttpResponse =
      response.copy(status = StatusCodes.BadGateway)
    val route = mapResponse(overwriteResultStatus)(complete("abc"))

    // tests:
    Get("/abcdef?ghi=12") ~> route ~> check {
      status shouldEqual StatusCodes.BadGateway
    }
    //#mapResponse0
  }
  "1mapResponse-advanced-json" in {
    //#mapResponse1-advanced
    trait ApiRoutes {
      protected def system: ActorSystem

      private val log = Logging(system, "ApiRoutes")

      private val NullJsonEntity = HttpEntity(ContentTypes.`application/json`, "{}")

      private def nonSuccessToEmptyJsonEntity(response: HttpResponse): HttpResponse =
        response.status match {
          case code if code.isSuccess => response
          case code =>
            log.warning("Dropping response entity since response status code was: {}", code)
            response.copy(entity = NullJsonEntity)
        }

      /** Wrapper for all of our JSON API routes */
      def apiRoute(innerRoutes: => Route): Route =
        mapResponse(nonSuccessToEmptyJsonEntity)(innerRoutes)
    }
    //#mapResponse1-advanced

    import StatusCodes._
    val __system = system
    val routes = new ApiRoutes {
      override protected def system = __system
    }
    import routes.apiRoute

    //#mapResponse1-advanced
    val route: Route =
      apiRoute {
        get {
          complete(InternalServerError)
        }
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "{}"
    }
    //#mapResponse1-advanced
  }
  "mapRouteResult" in {
    //#mapRouteResult
    // this directive is a joke, don't do that :-)
    val makeEverythingOk = mapRouteResult {
      case Complete(response) =>
        // "Everything is OK!"
        Complete(response.copy(status = 200))
      case r => r
    }

    val route =
      makeEverythingOk {
        // will actually render as 200 OK (!)
        complete(StatusCodes.Accepted)
      }

    // tests:
    Get("/") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
    //#mapRouteResult
  }
  "mapRouteResultFuture" in {
    //#mapRouteResultFuture
    val tryRecoverAddServer = mapRouteResultFuture { fr =>
      fr recover {
        case ex: IllegalArgumentException =>
          Complete(HttpResponse(StatusCodes.InternalServerError))
      } map {
        case Complete(res) => Complete(res.addHeader(Server("MyServer 1.0")))
        case rest          => rest
      }
    }

    val route =
      tryRecoverAddServer {
        complete("Hello world!")
      }

    // tests:
    Get("/") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      header[Server] shouldEqual Some(Server("MyServer 1.0"))
    }
    //#mapRouteResultFuture
  }
  "mapResponseEntity" in {
    //#mapResponseEntity
    def prefixEntity(entity: ResponseEntity): ResponseEntity = entity match {
      case HttpEntity.Strict(contentType, data) =>
        HttpEntity.Strict(contentType, ByteString("test") ++ data)
      case _ => throw new IllegalStateException("Unexpected entity type")
    }

    val prefixWithTest: Directive0 = mapResponseEntity(prefixEntity)
    val route = prefixWithTest(complete("abc"))

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "testabc"
    }
    //#mapResponseEntity
  }
  "mapResponseHeaders" in {
    //#mapResponseHeaders
    // adds all request headers to the response
    val echoRequestHeaders = extract(_.request.headers).flatMap(respondWithHeaders)

    val removeIdHeader = mapResponseHeaders(_.filterNot(_.lowercaseName == "id"))
    val route =
      removeIdHeader {
        echoRequestHeaders {
          complete("test")
        }
      }

    // tests:
    Get("/") ~> RawHeader("id", "12345") ~> RawHeader("id2", "67890") ~> route ~> check {
      header("id") shouldEqual None
      header("id2").get.value shouldEqual "67890"
    }
    //#mapResponseHeaders
  }
  "mapInnerRoute" in {
    //#mapInnerRoute
    val completeWithInnerException =
      mapInnerRoute { route => ctx =>
        try {
          route(ctx)
        } catch {
          case NonFatal(e) => ctx.complete(s"Got ${e.getClass.getSimpleName} '${e.getMessage}'")
        }
      }

    val route =
      completeWithInnerException {
        complete(throw new IllegalArgumentException("BLIP! BLOP! Everything broke"))
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Got IllegalArgumentException 'BLIP! BLOP! Everything broke'"
    }
    //#mapInnerRoute
  }
  "mapRejections" in {
    //#mapRejections
    // ignore any rejections and replace them by AuthorizationFailedRejection
    val replaceByAuthorizationFailed = mapRejections(_ => List(AuthorizationFailedRejection))
    val route =
      replaceByAuthorizationFailed {
        path("abc")(complete("abc"))
      }

    // tests:
    Get("/") ~> route ~> check {
      rejection shouldEqual AuthorizationFailedRejection
    }

    Get("/abc") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
    //#mapRejections
  }
  "recoverRejections" in {
    //#recoverRejections
    val authRejectionsToNothingToSeeHere = recoverRejections { rejections =>
      if (rejections.exists(_.isInstanceOf[AuthenticationFailedRejection]))
        Complete(HttpResponse(entity = "Nothing to see here, move along."))
      else if (rejections == Nil) // see "Empty Rejections" for more details
        Complete(HttpResponse(StatusCodes.NotFound, entity = "Literally nothing to see here."))
      else
        Rejected(rejections)
    }
    val neverAuth: Authenticator[String] = creds => None
    val alwaysAuth: Authenticator[String] = creds => Some("id")

    val route =
      authRejectionsToNothingToSeeHere {
        pathPrefix("auth") {
          path("never") {
            authenticateBasic("my-realm", neverAuth) { user =>
              complete("Welcome to the bat-cave!")
            }
          } ~
            path("always") {
              authenticateBasic("my-realm", alwaysAuth) { user =>
                complete("Welcome to the secret place!")
              }
            }
        }
      }

    // tests:
    Get("/auth/never") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Nothing to see here, move along."
    }
    Get("/auth/always") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Welcome to the secret place!"
    }
    Get("/auth/does_not_exist") ~> route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] shouldEqual "Literally nothing to see here."
    }
    //#recoverRejections
  }
  "recoverRejectionsWith" in {
    //#recoverRejectionsWith
    val authRejectionsToNothingToSeeHere = recoverRejectionsWith { rejections =>
      Future {
        // imagine checking rejections takes a longer time:
        if (rejections.exists(_.isInstanceOf[AuthenticationFailedRejection]))
          Complete(HttpResponse(entity = "Nothing to see here, move along."))
        else
          Rejected(rejections)
      }
    }
    val neverAuth: Authenticator[String] = creds => None

    val route =
      authRejectionsToNothingToSeeHere {
        pathPrefix("auth") {
          path("never") {
            authenticateBasic("my-realm", neverAuth) { user =>
              complete("Welcome to the bat-cave!")
            }
          }
        }
      }

    // tests:
    Get("/auth/never") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Nothing to see here, move along."
    }
    //#recoverRejectionsWith
  }
  "0mapRequest" in {
    //#mapRequest0
    def transformToPostRequest(req: HttpRequest): HttpRequest = req.copy(method = HttpMethods.POST)
    val route =
      mapRequest(transformToPostRequest) {
        extractRequest { req =>
          complete(s"The request method was ${req.method.name}")
        }
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "The request method was POST"
    }
    //#mapRequest0
  }
  "mapRequestContext" in {
    //#mapRequestContext
    val replaceRequest =
      mapRequestContext(_.withRequest(HttpRequest(HttpMethods.POST)))

    val route =
      replaceRequest {
        extractRequest { req =>
          complete(req.method.value)
        }
      }

    // tests:
    Get("/abc/def/ghi") ~> route ~> check {
      responseAs[String] shouldEqual "POST"
    }
    //#mapRequestContext
  }
  "0mapRouteResult" in {
    //#mapRouteResult0
    val rejectAll = // not particularly useful directive
      mapRouteResult {
        case _ => Rejected(List(AuthorizationFailedRejection))
      }
    val route =
      rejectAll {
        complete("abc")
      }

    // tests:
    Get("/") ~> route ~> check {
      rejections.nonEmpty shouldEqual true
    }
    //#mapRouteResult0
  }
  "mapRouteResultPF" in {
    //#mapRouteResultPF
    case object MyCustomRejection extends Rejection
    val rejectRejections = // not particularly useful directive
      mapRouteResultPF {
        case Rejected(_) => Rejected(List(AuthorizationFailedRejection))
      }
    val route =
      rejectRejections {
        reject(MyCustomRejection)
      }

    // tests:
    Get("/") ~> route ~> check {
      rejection shouldEqual AuthorizationFailedRejection
    }
    //#mapRouteResultPF
  }
  "mapRouteResultWithPF-0" in {
    //#mapRouteResultWithPF-0
    case object MyCustomRejection extends Rejection
    val rejectRejections = // not particularly useful directive
      mapRouteResultWithPF {
        case Rejected(_) => Future(Rejected(List(AuthorizationFailedRejection)))
      }
    val route =
      rejectRejections {
        reject(MyCustomRejection)
      }

    // tests:
    Get("/") ~> route ~> check {
      rejection shouldEqual AuthorizationFailedRejection
    }
    //#mapRouteResultWithPF-0
  }
  "mapRouteResultWith-0" in {
    //#mapRouteResultWith-0
    case object MyCustomRejection extends Rejection
    val rejectRejections = // not particularly useful directive
      mapRouteResultWith {
        case Rejected(_) => Future(Rejected(List(AuthorizationFailedRejection)))
        case res         => Future(res)
      }
    val route =
      rejectRejections {
        reject(MyCustomRejection)
      }

    // tests:
    Get("/") ~> route ~> check {
      rejection shouldEqual AuthorizationFailedRejection
    }
    //#mapRouteResultWith-0
  }
  "pass" in {
    //#pass
    val route = pass(complete("abc"))

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "abc"
    }
    //#pass
  }
  "0provide" in {
    //#provide0
    def providePrefixedString(value: String): Directive1[String] = provide("prefix:" + value)
    val route =
      providePrefixedString("test") { value =>
        complete(value)
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "prefix:test"
    }
    //#provide0
  }
  "cancelRejections-filter-example" in {
    //#cancelRejections-filter-example
    def isMethodRejection: Rejection => Boolean = {
      case MethodRejection(_) => true
      case _                  => false
    }

    val route =
      cancelRejections(isMethodRejection) {
        post {
          complete("Result")
        }
      }

    // tests:
    Get("/") ~> route ~> check {
      rejections shouldEqual Nil
      handled shouldEqual false
    }
    //#cancelRejections-filter-example
  }
  "cancelRejection-example" in {
    //#cancelRejection-example
    val route =
      cancelRejection(MethodRejection(HttpMethods.POST)) {
        post {
          complete("Result")
        }
      }

    // tests:
    Get("/") ~> route ~> check {
      rejections shouldEqual Nil
      handled shouldEqual false
    }
    //#cancelRejection-example
  }
  "extractRequest-example" in {
    //#extractRequest-example
    val route =
      extractRequest { request =>
        complete(s"Request method is ${request.method.name} and content-type is ${request.entity.contentType}")
      }

    // tests:
    Post("/", "text") ~> route ~> check {
      responseAs[String] shouldEqual "Request method is POST and content-type is text/plain; charset=UTF-8"
    }
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Request method is GET and content-type is none/none"
    }
    //#extractRequest-example
  }
  "extractSettings-examples" in {
    //#extractSettings-examples
    val route =
      extractSettings { settings: RoutingSettings =>
        complete(s"RoutingSettings.renderVanityFooter = ${settings.renderVanityFooter}")
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "RoutingSettings.renderVanityFooter = true"
    }
    //#extractSettings-examples
  }
  "mapSettings-examples" in {
    //#mapSettings-examples
    val tunedSettings = mapSettings { settings =>
      settings.withFileGetConditional(false)
    }

    val route =
      tunedSettings {
        extractSettings { settings: RoutingSettings =>
          complete(s"RoutingSettings.fileGetConditional = ${settings.fileGetConditional}")
        }
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual s"RoutingSettings.fileGetConditional = false"
    }
    //#mapSettings-examples
  }
  "extractMatchedPath-example" in {
    //#extractMatchedPath-example
    val route =
      pathPrefix("abc") {
        extractMatchedPath { matched =>
          complete(matched.toString)
        }
      }

    // tests:
    Get("/abc") ~> route ~> check {
      responseAs[String] shouldEqual "/abc"
    }
    Get("/abc/xyz") ~> route ~> check {
      responseAs[String] shouldEqual "/abc"
    }

    //#extractMatchedPath-example
  }
  "extractRequestContext-example" in {
    //#extractRequestContext-example
    val route =
      extractRequestContext { ctx =>
        ctx.log.debug("Using access to additional context available, like the logger.")
        val request = ctx.request
        complete(s"Request method is ${request.method.name} and content-type is ${request.entity.contentType}")
      }

    // tests:
    Post("/", "text") ~> route ~> check {
      responseAs[String] shouldEqual "Request method is POST and content-type is text/plain; charset=UTF-8"
    }
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Request method is GET and content-type is none/none"
    }
    //#extractRequestContext-example
  }
  "extractParserSettings-example" in {
    //#extractParserSettings-example
    val route =
      extractParserSettings { parserSettings =>
        complete(s"URI parsing mode is ${parserSettings.uriParsingMode}")
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "URI parsing mode is Strict"
    }
    //#extractParserSettings-example
  }
  "extractUri-example" in {
    //#extractUri-example
    val route =
      extractUri { uri =>
        complete(s"Full URI: $uri")
      }

    // tests:
    Get("/") ~> route ~> check {
      // tests are executed with the host assumed to be "example.com"
      responseAs[String] shouldEqual "Full URI: http://example.com/"
    }
    Get("/test") ~> route ~> check {
      responseAs[String] shouldEqual "Full URI: http://example.com/test"
    }
    //#extractUri-example
  }
  "mapUnmatchedPath-example" in {
    //#mapUnmatchedPath-example
    def ignore456(path: Uri.Path) = path match {
      case s @ Uri.Path.Segment(head, tail) if head.startsWith("456") =>
        val newHead = head.drop(3)
        if (newHead.isEmpty) tail
        else s.copy(head = head.drop(3))
      case _ => path
    }
    val ignoring456 = mapUnmatchedPath(ignore456)

    val route =
      pathPrefix("123") {
        ignoring456 {
          path("abc") {
            complete("Content")
          }
        }
      }

    // tests:
    Get("/123/abc") ~> route ~> check {
      responseAs[String] shouldEqual "Content"
    }
    Get("/123456/abc") ~> route ~> check {
      responseAs[String] shouldEqual "Content"
    }
    //#mapUnmatchedPath-example
  }
  "extractUnmatchedPath-example" in {
    //#extractUnmatchedPath-example
    val route =
      pathPrefix("abc") {
        extractUnmatchedPath { remaining =>
          complete(s"Unmatched: '$remaining'")
        }
      }

    // tests:
    Get("/abc") ~> route ~> check {
      responseAs[String] shouldEqual "Unmatched: ''"
    }
    Get("/abc/456") ~> route ~> check {
      responseAs[String] shouldEqual "Unmatched: '/456'"
    }
    //#extractUnmatchedPath-example
  }
  "extractRequestEntity-example" in {
    //#extractRequestEntity-example
    val route =
      extractRequestEntity { entity =>
        complete(s"Request entity content-type is ${entity.contentType}")
      }

    // tests:
    val httpEntity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "req")
    Post("/abc", httpEntity) ~> route ~> check {
      responseAs[String] shouldEqual "Request entity content-type is text/plain; charset=UTF-8"
    }
    //#extractRequestEntity-example
  }
  "extractDataBytes-example" in {
    //#extractDataBytes-example
    val route =
      extractDataBytes { data ⇒
        val sum = data.runFold(0) { (acc, i) ⇒ acc + i.utf8String.toInt }
        onSuccess(sum) { s ⇒
          complete(HttpResponse(entity = HttpEntity(s.toString)))
        }
      }

    // tests:
    val dataBytes = Source.fromIterator(() ⇒ Iterator.range(1, 10).map(x ⇒ ByteString(x.toString)))
    Post("/abc", HttpEntity(ContentTypes.`text/plain(UTF-8)`, data = dataBytes)) ~> route ~> check {
      responseAs[String] shouldEqual "45"
    }
    //#extractDataBytes-example
  }
  "extractStrictEntity-example" in {
    //#extractStrictEntity-example
    import scala.concurrent.duration._
    val route = extractStrictEntity(3.seconds) { entity =>
      complete(entity.data.utf8String)
    }

    // tests:
    val dataBytes = Source.fromIterator(() ⇒ Iterator.range(1, 10).map(x ⇒ ByteString(x.toString)))
    Post("/", HttpEntity(ContentTypes.`text/plain(UTF-8)`, data = dataBytes)) ~> route ~> check {
      responseAs[String] shouldEqual "123456789"
    }
    //#extractStrictEntity-example
  }
  "toStrictEntity-example" in {
    //#toStrictEntity-example
    import scala.concurrent.duration._
    val route = toStrictEntity(3.seconds) {
      extractRequest { req =>
        req.entity match {
          case strict: HttpEntity.Strict =>
            complete(s"Request entity is strict, data=${strict.data.utf8String}")
          case _ =>
            complete("Ooops, request entity is not strict!")
        }
      }
    }

    // tests:
    val dataBytes = Source.fromIterator(() ⇒ Iterator.range(1, 10).map(x ⇒ ByteString(x.toString)))
    Post("/", HttpEntity(ContentTypes.`text/plain(UTF-8)`, data = dataBytes)) ~> route ~> check {
      responseAs[String] shouldEqual "Request entity is strict, data=123456789"
    }
    //#toStrictEntity-example
  }

  "extractActorSystem-example" in {
    //#extractActorSystem-example
    val route = extractActorSystem { actorSystem =>
      complete(s"Actor System extracted, hash=${actorSystem.hashCode()}")
    }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual s"Actor System extracted, hash=${system.hashCode()}"
    }
    //#extractActorSystem-example
  }

}
