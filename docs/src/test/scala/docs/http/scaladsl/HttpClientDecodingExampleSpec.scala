/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.Future

class HttpClientDecodingExampleSpec extends WordSpec with Matchers with CompileOnlySpec {
  "single-request-decoding-example" in compileOnlySpec {
    //#single-request-decoding-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.coding.{ Gzip, Deflate, NoCoding }
    import akka.http.scaladsl.model._, headers.HttpEncodings
    import akka.stream.ActorMaterializer

    import scala.concurrent.Future

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    val http = Http()

    val requests: Seq[HttpRequest] = Seq(
      "https://httpbin.org/gzip", // Content-Encoding: gzip in response
      "https://httpbin.org/deflate", // Content-Encoding: deflate in response
      "https://httpbin.org/get" // no Content-Encoding in response
    ).map(uri ⇒ HttpRequest(uri = uri))

    def decodeResponse(response: HttpResponse): HttpResponse = {
      val decoder = response.encoding match {
        case HttpEncodings.gzip ⇒
          Gzip
        case HttpEncodings.deflate ⇒
          Deflate
        case HttpEncodings.identity ⇒
          NoCoding
      }

      decoder.decode(response)
    }

    val futureResponses: Future[Seq[HttpResponse]] =
      Future.traverse(requests)(http.singleRequest(_).map(decodeResponse))
    //#single-request-decoding-example
  }
}
