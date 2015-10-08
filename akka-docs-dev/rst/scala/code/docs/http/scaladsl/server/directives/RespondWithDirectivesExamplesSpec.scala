/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.model.headers._

class RespondWithDirectivesExamplesSpec extends RoutingSpec {

  "respondWithHeader-0" in {
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

  "respondWithDefaultHeader-0" in {
    // custom headers
    val blippy = RawHeader("X-Fish-Name", "Blippy")
    val elTonno = RawHeader("X-Fish-Name", "El Tonno")

    // format: OFF
    // by default always include the Blippy header,
    // unless a more specific X-Fish-Name is given by the inner route
    val route =
      respondWithDefaultHeader(blippy) {  //  blippy
        respondWithHeader(elTonno) {      // /  el tonno
          path("el-tonno") {              // | /
            complete("¡Ay blippy!")       // | |- el tonno
          } ~                             // | |
          path("los-tonnos") {            // | |
            complete("¡Ay ay blippy!")    // | |- el tonno
          }                               // | |
        } ~                               // | x
        complete("Blip!")                 // |- blippy
      } // x
    // format: ON

    Get("/") ~> route ~> check {
      header("X-Fish-Name") shouldEqual Some(RawHeader("X-Fish-Name", "Blippy"))
      responseAs[String] shouldEqual "Blip!"
    }

    Get("/el-tonno") ~> route ~> check {
      header("X-Fish-Name") shouldEqual Some(RawHeader("X-Fish-Name", "El Tonno"))
      responseAs[String] shouldEqual "¡Ay blippy!"
    }

    Get("/los-tonnos") ~> route ~> check {
      header("X-Fish-Name") shouldEqual Some(RawHeader("X-Fish-Name", "El Tonno"))
      responseAs[String] shouldEqual "¡Ay ay blippy!"
    }
  }
  // format: ON

  "respondWithHeaders-0" in {
    val route =
      path("foo") {
        respondWithHeaders(RawHeader("Funky-Muppet", "gonzo"), Origin(HttpOrigin("http://akka.io"))) {
          complete("beep")
        }
      }

    Get("/foo") ~> route ~> check {
      header("Funky-Muppet") shouldEqual Some(RawHeader("Funky-Muppet", "gonzo"))
      header[Origin] shouldEqual Some(Origin(HttpOrigin("http://akka.io")))
      responseAs[String] shouldEqual "beep"
    }
  }

  //  FIXME awaiting resolution of https://github.com/akka/akka/issues/18625
  //  "respondWithMediaType-examples" in {
  //    import MediaTypes._
  //
  //    val route =
  //      path("foo") {
  //        respondWithMediaType(`application/json`) {
  //          complete("[]") // marshalled to `text/plain` here
  //        }
  //      }
  //
  //    Get("/foo") ~> route ~> check {
  //      mediaType shouldEqual `application/json`
  //      responseAs[String] shouldEqual "[]"
  //    }
  //
  //    Get("/foo") ~> Accept(MediaRanges.`text/*`) ~> route ~> check {
  //      rejection shouldEqual UnacceptedResponseContentTypeRejection(ContentType(`application/json`) :: Nil)
  //    }
  //  }

  //  "respondWithSingletonHeader-examples" in {
  //    val respondWithMuppetHeader =
  //      respondWithSingletonHeader(RawHeader("Funky-Muppet", "gonzo"))
  //
  //    val route =
  //      path("foo") {
  //        respondWithMuppetHeader {
  //          complete("beep")
  //        }
  //      } ~
  //        path("bar") {
  //          respondWithMuppetHeader {
  //            respondWithHeader(RawHeader("Funky-Muppet", "kermit")) {
  //              complete("beep")
  //            }
  //          }
  //        }
  //
  //    Get("/foo") ~> route ~> check {
  //      headers.filter(_.is("funky-muppet")) shouldEqual List(RawHeader("Funky-Muppet", "gonzo"))
  //      responseAs[String] shouldEqual "beep"
  //    }
  //
  //    Get("/bar") ~> route ~> check {
  //      headers.filter(_.is("funky-muppet")) shouldEqual List(RawHeader("Funky-Muppet", "kermit"))
  //      responseAs[String] shouldEqual "beep"
  //    }
  //  }

}