/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers

class ParameterDirectivesExamplesSpec extends RoutingSpec with PredefinedFromStringUnmarshallers {
  "example-1" in {
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
  }
  "required-1" in {
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
  }
  "optional" in {
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
  }
  "optional-with-default" in {
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
  }
  "required-value" in {
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
  }
  "mapped-value" in {
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
  }
  "repeated" in {
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
  }
  "mapped-repeated" in {
    val route =
      parameters('color, 'distance.as[Int].*) { (color, cities) =>
        cities.toList match {
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
  }
  "parameterMap" in {
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
  }
  "parameterMultiMap" in {
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
  }
  "parameterSeq" in {
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
  }
  "csv" in {
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
  }
}
