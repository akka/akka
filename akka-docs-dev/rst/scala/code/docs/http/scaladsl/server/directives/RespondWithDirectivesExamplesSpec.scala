/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives
/*
import akka.http.scaladsl.server.UnacceptedResponseContentTypeRejection
import akka.http.scaladsl.model._
import headers._

class RespondWithDirectivesExamplesSpec extends RoutingSpec {

  "respondWithHeader-examples" in {
    val route =
      path("foo") {
        respondWithHeader(RawHeader("Funky-Muppet", "gonzo")) {
          complete("beep")
        }
      }

    Get("/foo") ~> route ~> check {
      header("Funky-Muppet") shouldEqual Some(RawHeader("Funky-Muppet", "gonzo"))
      responseAs[String] shouldEqual "beep"
    }
  }

  "respondWithHeaders-examples" in {
    val route =
      path("foo") {
        respondWithHeaders(RawHeader("Funky-Muppet", "gonzo"), Origin(Seq(HttpOrigin("http://akka.io")))) {
          complete("beep")
        }
      }

    Get("/foo") ~> route ~> check {
      header("Funky-Muppet") shouldEqual Some(RawHeader("Funky-Muppet", "gonzo"))
      header[Origin] shouldEqual Some(Origin(Seq(HttpOrigin("http://akka.io"))))
      responseAs[String] shouldEqual "beep"
    }
  }

  "respondWithMediaType-examples" in {
    import MediaTypes._

    val route =
      path("foo") {
        respondWithMediaType(`application/json`) {
          complete("[]") // marshalled to `text/plain` here
        }
      }

    Get("/foo") ~> route ~> check {
      mediaType shouldEqual `application/json`
      responseAs[String] shouldEqual "[]"
    }
    */
//Get("/foo") ~> Accept(MediaRanges.`text/*`) ~> route ~> check {
/*  rejection shouldEqual UnacceptedResponseContentTypeRejection(ContentType(`application/json`) :: Nil)
    }
  }

  "respondWithSingletonHeader-examples" in {
    val respondWithMuppetHeader =
      respondWithSingletonHeader(RawHeader("Funky-Muppet", "gonzo"))

    val route =
      path("foo") {
        respondWithMuppetHeader {
          complete("beep")
        }
      } ~
        path("bar") {
          respondWithMuppetHeader {
            respondWithHeader(RawHeader("Funky-Muppet", "kermit")) {
              complete("beep")
            }
          }
        }

    Get("/foo") ~> route ~> check {
      headers.filter(_.is("funky-muppet")) shouldEqual List(RawHeader("Funky-Muppet", "gonzo"))
      responseAs[String] shouldEqual "beep"
    }

    Get("/bar") ~> route ~> check {
      headers.filter(_.is("funky-muppet")) shouldEqual List(RawHeader("Funky-Muppet", "kermit"))
      responseAs[String] shouldEqual "beep"
    }
  }

  "respondWithStatus-examples" in {
    val route =
      path("foo") {
        respondWithStatus(201) {
          complete("beep")
        }
      }

    Get("/foo") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[String] shouldEqual "beep"
    }
  }
}
*/ 