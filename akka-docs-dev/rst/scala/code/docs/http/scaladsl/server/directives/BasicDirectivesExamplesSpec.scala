/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import scala.util.control.NonFatal
import akka.util.ByteString
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._

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
  "0mapRouteResponse" in {
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
}
