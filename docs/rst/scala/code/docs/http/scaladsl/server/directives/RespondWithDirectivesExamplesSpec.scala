/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.headers._
import docs.http.scaladsl.server.RoutingSpec

class RespondWithDirectivesExamplesSpec extends RoutingSpec {

  "respondWithHeader-0" in {
    val route =
      path("foo") {
        respondWithHeader(RawHeader("Funky-Muppet", "gonzo")) {
          complete("beep")
        }
      }

    // tests:
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

    // tests:
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

    // tests:
    Get("/foo") ~> route ~> check {
      header("Funky-Muppet") shouldEqual Some(RawHeader("Funky-Muppet", "gonzo"))
      header[Origin] shouldEqual Some(Origin(HttpOrigin("http://akka.io")))
      responseAs[String] shouldEqual "beep"
    }
  }

}