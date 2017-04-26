/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers
import docs.http.scaladsl.server.RoutingSpec

class ParameterDirectivesExamplesSpec extends RoutingSpec with PredefinedFromStringUnmarshallers {
  "example-1" in {
    //#example-1
    val route =
      parameter('color) { color =>
        complete(s"The color is '$color'")
      }

    // tests:
    Get("/?color=blue") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue'"
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] shouldEqual "Request is missing required query parameter 'color'"
    }
    //#example-1
  }
  "required-1" in {
    //#required-1
    val route =
      parameters('color, 'backgroundColor) { (color, backgroundColor) =>
        complete(s"The color is '$color' and the background is '$backgroundColor'")
      }

    // tests:
    Get("/?color=blue&backgroundColor=red") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and the background is 'red'"
    }
    Get("/?color=blue") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] shouldEqual "Request is missing required query parameter 'backgroundColor'"
    }
    //#required-1
  }
  "optional" in {
    //#optional
    val route =
      parameters('color, 'backgroundColor.?) { (color, backgroundColor) =>
        val backgroundStr = backgroundColor.getOrElse("<undefined>")
        complete(s"The color is '$color' and the background is '$backgroundStr'")
      }

    // tests:
    Get("/?color=blue&backgroundColor=red") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and the background is 'red'"
    }
    Get("/?color=blue") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and the background is '<undefined>'"
    }
    //#optional
  }
  "optional-with-default" in {
    //#optional-with-default
    val route =
      parameters('color, 'backgroundColor ? "white") { (color, backgroundColor) =>
        complete(s"The color is '$color' and the background is '$backgroundColor'")
      }

    // tests:
    Get("/?color=blue&backgroundColor=red") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and the background is 'red'"
    }
    Get("/?color=blue") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and the background is 'white'"
    }
    //#optional-with-default
  }
  "required-value" in {
    //#required-value
    val route =
      parameters('color, 'action ! "true") { (color) =>
        complete(s"The color is '$color'.")
      }

    // tests:
    Get("/?color=blue&action=true") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue'."
    }

    Get("/?color=blue&action=false") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] shouldEqual "The requested resource could not be found."
    }
    //#required-value
  }
  "mapped-value" in {
    //#mapped-value
    val route =
      parameters('color, 'count.as[Int]) { (color, count) =>
        complete(s"The color is '$color' and you have $count of it.")
      }

    // tests:
    Get("/?color=blue&count=42") ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and you have 42 of it."
    }

    Get("/?color=blue&count=blub") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] shouldEqual "The query parameter 'count' was malformed:\n'blub' is not a valid 32-bit signed integer value"
    }
    //#mapped-value
  }
  "repeated" in {
    //#repeated
    val route =
      parameters('color, 'city.*) { (color, cities) =>
        cities.toList match {
          case Nil         => complete(s"The color is '$color' and there are no cities.")
          case city :: Nil => complete(s"The color is '$color' and the city is $city.")
          case multiple    => complete(s"The color is '$color' and the cities are ${multiple.mkString(", ")}.")
        }
      }

    // tests:
    Get("/?color=blue") ~> route ~> check {
      responseAs[String] === "The color is 'blue' and there are no cities."
    }

    Get("/?color=blue&city=Chicago") ~> Route.seal(route) ~> check {
      responseAs[String] === "The color is 'blue' and the city is Chicago."
    }

    Get("/?color=blue&city=Chicago&city=Boston") ~> Route.seal(route) ~> check {
      responseAs[String] === "The color is 'blue' and the cities are Chicago, Boston."
    }
    //#repeated
  }
  "mapped-repeated" in {
    //#mapped-repeated
    val route =
      parameters('color, 'distance.as[Int].*) { (color, distances) =>
        distances.toList match {
          case Nil             => complete(s"The color is '$color' and there are no distances.")
          case distance :: Nil => complete(s"The color is '$color' and the distance is $distance.")
          case multiple        => complete(s"The color is '$color' and the distances are ${multiple.mkString(", ")}.")
        }
      }

    // tests:
    Get("/?color=blue") ~> route ~> check {
      responseAs[String] === "The color is 'blue' and there are no distances."
    }

    Get("/?color=blue&distance=5") ~> Route.seal(route) ~> check {
      responseAs[String] === "The color is 'blue' and the distance is 5."
    }

    Get("/?color=blue&distance=5&distance=14") ~> Route.seal(route) ~> check {
      responseAs[String] === "The color is 'blue' and the distances are 5, 14."
    }
    //#mapped-repeated
  }
  "parameterMap" in {
    //#parameterMap
    val route =
      parameterMap { params =>
        def paramString(param: (String, String)): String = s"""${param._1} = '${param._2}'"""
        complete(s"The parameters are ${params.map(paramString).mkString(", ")}")
      }

    // tests:
    Get("/?color=blue&count=42") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are color = 'blue', count = '42'"
    }
    Get("/?x=1&x=2") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are x = '2'"
    }
    //#parameterMap
  }
  "parameterMultiMap" in {
    //#parameterMultiMap
    val route =
      parameterMultiMap { params =>
        complete(s"There are parameters ${params.map(x => x._1 + " -> " + x._2.size).mkString(", ")}")
      }

    // tests:
    Get("/?color=blue&count=42") ~> route ~> check {
      responseAs[String] shouldEqual "There are parameters color -> 1, count -> 1"
    }
    Get("/?x=23&x=42") ~> route ~> check {
      responseAs[String] shouldEqual "There are parameters x -> 2"
    }
    //#parameterMultiMap
  }
  "parameterSeq" in {
    //#parameterSeq
    val route =
      parameterSeq { params =>
        def paramString(param: (String, String)): String = s"""${param._1} = '${param._2}'"""
        complete(s"The parameters are ${params.map(paramString).mkString(", ")}")
      }

    // tests:
    Get("/?color=blue&count=42") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are color = 'blue', count = '42'"
    }
    Get("/?x=1&x=2") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are x = '1', x = '2'"
    }
    //#parameterSeq
  }
  "csv" in {
    //#csv
    val route =
      parameter("names".as(CsvSeq[String])) { names =>
        complete(s"The parameters are ${names.mkString(", ")}")
      }

    // tests:
    Get("/?names=") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are "
    }
    Get("/?names=Caplin") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are Caplin"
    }
    Get("/?names=Caplin,John") ~> route ~> check {
      responseAs[String] shouldEqual "The parameters are Caplin, John"
    }
    //#csv
  }
}
