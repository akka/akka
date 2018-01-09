/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.server.{ Directive, Directive1, Route }
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
      responseAs[String] shouldEqual "7"
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
      responseAs[String] shouldEqual "7"
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
      responseAs[String] shouldEqual "42"
    }
    Get("/?a=-18") ~> myDirective(i => complete(i.toString)) ~> check {
      handled shouldEqual false
    }
    //#flatMap-0
  }

  "scratch-1" in {
    //#scratch-1
    def hostnameAndPort: Directive[(String, Int)] = Directive[(String, Int)] { inner => ctx =>
      val authority = ctx.request.uri.authority
      inner((authority.host.address(), authority.port))(ctx)
    }

    // test
    val route = hostnameAndPort {
      (hostname, port) => complete(s"The hostname is $hostname and the port is $port")
    }

    Get() ~> Host("akka.io", 8080) ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "The hostname is akka.io and the port is 8080"
    }
    //#scratch-1
  }

  "scratch-2" in {
    //#scratch-2
    object hostnameAndPort extends Directive[(String, Int)] {
      override def tapply(f: ((String, Int)) => Route): Route = { ctx =>
        val authority = ctx.request.uri.authority
        f((authority.host.address(), authority.port))(ctx)
      }
    }

    // test
    val route = hostnameAndPort {
      (hostname, port) => complete(s"The hostname is $hostname and the port is $port")
    }

    Get() ~> Host("akka.io", 8080) ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "The hostname is akka.io and the port is 8080"
    }
    //#scratch-2
  }

}
