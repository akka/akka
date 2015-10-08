/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import java.io.File

import akka.event.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.stream.io.SynchronousFileSource
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString

import scala.concurrent.Future
import scala.util.control.NonFatal

class BasicDirectivesExamplesSpec extends RoutingSpec {
  "0extract" in {
    val uriLength = extract(_.request.uri.toString.length)
    val route =
      uriLength { len =>
        complete(s"The length of the request URI is $len")
      }

    Get("/abcdef") ~> route ~> check {
      responseAs[String] shouldEqual "The length of the request URI is 25"
    }
  }
  "0extractLog" in {
    val route =
      extractLog { log =>
        log.debug("I'm logging things in much detail..!")
        complete("It's amazing!")
      }

    Get("/abcdef") ~> route ~> check {
      responseAs[String] shouldEqual "It's amazing!"
    }
  }
  "0withMaterializer" in {
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

    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Materialized by ${materializer.##}!"
    }
    Get("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Materialized by ${special.##}!"
    }
  }
  "0withExecutionContext" in compileOnlySpec {
    val special = system.dispatchers.lookup("special")

    def sample() =
      path("sample") {
        extractExecutionContext { implicit ec =>
          complete {
            Future(s"Run on ${ec.##}!") // uses the `ec` ExecutionContext
          }
        }
      }

    val route =
      pathPrefix("special") {
        withExecutionContext(special) {
          sample() // `special` execution context will be used
        }
      } ~ sample() // default execution context will be used

    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Run on ${system.dispatcher.##}!"
    }
    Get("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Run on ${special.##}!"
    }
  }
  "0withLog" in {
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

    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Logging using ${system.log}!"
    }
    Get("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"Logging using $special!"
    }
  }
  "0withSettings" in compileOnlySpec {
    val special = RoutingSettings(system).copy(fileIODispatcher = "special-io-dispatcher")

    def sample() =
      path("sample") {
        complete {
          // internally uses the configured fileIODispatcher:
          val source = SynchronousFileSource(new File("example.json"))
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

    Post("/special/sample") ~> route ~> check {
      responseAs[String] shouldEqual s"{}"
    }
    Get("/sample") ~> route ~> check {
      responseAs[String] shouldEqual "{}"
    }
  }
  "textract" in {
    val pathAndQuery = textract { ctx =>
      val uri = ctx.request.uri
      (uri.path, uri.query)
    }
    val route =
      pathAndQuery { (p, query) =>
        complete(s"The path is $p and the query is $query")
      }

    Get("/abcdef?ghi=12") ~> route ~> check {
      responseAs[String] shouldEqual "The path is /abcdef and the query is ghi=12"
    }
  }
  "tprovide" in {
    def provideStringAndLength(value: String) = tprovide((value, value.length))
    val route =
      provideStringAndLength("test") { (value, len) =>
        complete(s"Value is $value and its length is $len")
      }
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Value is test and its length is 4"
    }
  }
  "0mapResponse" in {
    def overwriteResultStatus(response: HttpResponse): HttpResponse =
      response.copy(status = StatusCodes.BadGateway)
    val route = mapResponse(overwriteResultStatus)(complete("abc"))

    Get("/abcdef?ghi=12") ~> route ~> check {
      status shouldEqual StatusCodes.BadGateway
    }
  }
  "mapResponseEntity" in {
    def prefixEntity(entity: ResponseEntity): ResponseEntity = entity match {
      case HttpEntity.Strict(contentType, data) =>
        HttpEntity.Strict(contentType, ByteString("test") ++ data)
      case _ => throw new IllegalStateException("Unexpected entity type")
    }

    val prefixWithTest: Directive0 = mapResponseEntity(prefixEntity)
    val route = prefixWithTest(complete("abc"))

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "testabc"
    }
  }
  "mapResponseHeaders" in {
    // adds all request headers to the response
    val echoRequestHeaders = extract(_.request.headers).flatMap(respondWithHeaders)

    val removeIdHeader = mapResponseHeaders(_.filterNot(_.lowercaseName == "id"))
    val route =
      removeIdHeader {
        echoRequestHeaders {
          complete("test")
        }
      }

    Get("/") ~> RawHeader("id", "12345") ~> RawHeader("id2", "67890") ~> route ~> check {
      header("id") shouldEqual None
      header("id2").get.value shouldEqual "67890"
    }
  }
  "mapInnerRoute" in {
    val completeWithInnerException =
      mapInnerRoute { route =>
        ctx =>
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

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Got IllegalArgumentException 'BLIP! BLOP! Everything broke'"
    }
  }
  "mapRejections" in {
    // ignore any rejections and replace them by AuthorizationFailedRejection
    val replaceByAuthorizationFailed = mapRejections(_ => List(AuthorizationFailedRejection))
    val route =
      replaceByAuthorizationFailed {
        path("abc")(complete("abc"))
      }

    Get("/") ~> route ~> check {
      rejection shouldEqual AuthorizationFailedRejection
    }
  }
  "0mapRequest" in {
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
  }
  "mapRequestContext" in {
    val replaceRequest =
      mapRequestContext(_.withRequest(HttpRequest(HttpMethods.POST)))

    val route =
      replaceRequest {
        extractRequest { req =>
          complete(req.method.value)
        }
      }

    Get("/abc/def/ghi") ~> route ~> check {
      responseAs[String] shouldEqual "POST"
    }
  }
  "0mapRouteResult" in {
    val rejectAll = // not particularly useful directive
      mapRouteResult {
        case _ => Rejected(List(AuthorizationFailedRejection))
      }
    val route =
      rejectAll {
        complete("abc")
      }

    Get("/") ~> route ~> check {
      rejections.nonEmpty shouldEqual true
    }
  }
  "mapRouteResultPF" in {
    case object MyCustomRejection extends Rejection
    val rejectRejections = // not particularly useful directive
      mapRouteResultPF {
        case Rejected(_) => Rejected(List(AuthorizationFailedRejection))
      }
    val route =
      rejectRejections {
        reject(MyCustomRejection)
      }

    Get("/") ~> route ~> check {
      rejection shouldEqual AuthorizationFailedRejection
    }
  }
  "pass" in {
    Get("/") ~> pass(complete("abc")) ~> check {
      responseAs[String] shouldEqual "abc"
    }
  }
  "0provide" in {
    def providePrefixedString(value: String): Directive1[String] = provide("prefix:" + value)
    val route =
      providePrefixedString("test") { value =>
        complete(value)
      }
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "prefix:test"
    }
  }
  "cancelRejections-filter-example" in {
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

    Get("/") ~> route ~> check {
      rejections shouldEqual Nil
      handled shouldEqual false
    }
  }
  "cancelRejection-example" in {
    val route =
      cancelRejection(MethodRejection(HttpMethods.POST)) {
        post {
          complete("Result")
        }
      }

    Get("/") ~> route ~> check {
      rejections shouldEqual Nil
      handled shouldEqual false
    }
  }
  "extractRequest-example" in {
    val route =
      extractRequest { request =>
        complete(s"Request method is ${request.method.name} and content-type is ${request.entity.contentType}")
      }

    Post("/", "text") ~> route ~> check {
      responseAs[String] shouldEqual "Request method is POST and content-type is text/plain; charset=UTF-8"
    }
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "Request method is GET and content-type is none/none"
    }
  }
  "extractUri-example" in {
    val route =
      extractUri { uri =>
        complete(s"Full URI: $uri")
      }

    Get("/") ~> route ~> check {
      // tests are executed with the host assumed to be "example.com"
      responseAs[String] shouldEqual "Full URI: http://example.com/"
    }
    Get("/test") ~> route ~> check {
      responseAs[String] shouldEqual "Full URI: http://example.com/test"
    }
  }
  "mapUnmatchedPath-example" in {
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
            complete(s"Content")
          }
        }
      }

    Get("/123/abc") ~> route ~> check {
      responseAs[String] shouldEqual "Content"
    }
    Get("/123456/abc") ~> route ~> check {
      responseAs[String] shouldEqual "Content"
    }
  }
  "extractUnmatchedPath-example" in {
    val route =
      pathPrefix("abc") {
        extractUnmatchedPath { remaining =>
          complete(s"Unmatched: '$remaining'")
        }
      }

    Get("/abc") ~> route ~> check {
      responseAs[String] shouldEqual "Unmatched: ''"
    }
    Get("/abc/456") ~> route ~> check {
      responseAs[String] shouldEqual "Unmatched: '/456'"
    }
  }

  private def compileOnlySpec(block: => Unit) = pending
}
