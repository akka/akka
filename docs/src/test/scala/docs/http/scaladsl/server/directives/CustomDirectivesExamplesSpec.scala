/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl.server.directives

import akka.http.scaladsl.server.{ Directive1, Directive }
import docs.http.scaladsl.server.RoutingSpec

class CustomDirectivesExamplesSpec extends RoutingSpec {

  "labeling" in {
    //#labeling
    val getOrPut = get | put

    // tests:
    val route = getOrPut { complete("ok") }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "ok"
    }

    Put("/") ~> route ~> check {
      responseAs[String] shouldEqual "ok"
    }
    //#labeling
  }

  "map-0" in {
    //#map-0
    val textParam: Directive1[String] =
      parameter("text".as[String])

    val lengthDirective: Directive1[Int] =
      textParam.map(text => text.length)

    // tests:
    Get("/?text=abcdefg") ~> lengthDirective(x => complete(x.toString)) ~> check {
      responseAs[String] === "7"
    }
    //#map-0
  }

  "tmap-1" in {
    //#tmap-1
    val twoIntParameters: Directive[(Int, Int)] =
      parameters(("a".as[Int], "b".as[Int]))

    val myDirective: Directive1[String] =
      twoIntParameters.tmap {
        case (a, b) => (a + b).toString
      }

    // tests:
    Get("/?a=2&b=5") ~> myDirective(x => complete(x)) ~> check {
      responseAs[String] === "7"
    }
    //#tmap-1
  }

  "flatMap-0" in {
    //#flatMap-0
    val intParameter: Directive1[Int] = parameter("a".as[Int])

    val myDirective: Directive1[Int] =
      intParameter.flatMap {
        case a if a > 0 => provide(2 * a)
        case _          => reject
      }

    // tests:
    Get("/?a=21") ~> myDirective(i => complete(i.toString)) ~> check {
      responseAs[String] === "42"
    }
    Get("/?a=-18") ~> myDirective(i => complete(i.toString)) ~> check {
      handled === false
    }
    //#flatMap-0
  }

}
