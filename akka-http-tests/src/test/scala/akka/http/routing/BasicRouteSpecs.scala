/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import akka.http.model.HttpMethods._
import akka.http.routing.PathMatchers.{ Segment, IntNumber }

class BasicRouteSpecs extends RoutingSpec {

  "routes created by the concatenation operator '~'" should {
    "yield the first sub route if it succeeded" in {
      Get() ~> {
        get { complete("first") } ~ get { complete("second") }
      } ~> check { responseAs[String] shouldEqual "first" }
    }
    "yield the second sub route if the first did not succeed" in {
      Get() ~> {
        post { complete("first") } ~ get { complete("second") }
      } ~> check { responseAs[String] shouldEqual "second" }
    }
    "collect rejections from both sub routes" in {
      Delete() ~> {
        get { completeOk } ~ put { completeOk }
      } ~> check { rejections shouldEqual Seq(MethodRejection(GET), MethodRejection(PUT)) }
    }
    "clear rejections that have already been 'overcome' by previous directives" in {
      pending
      /*Put() ~> {
        put { parameter('yeah) { echoComplete } } ~
          get { completeOk }
      } ~> check { rejection shouldEqual MissingQueryParamRejection("yeah") }*/
    }
  }

  "Route conjunction" should {
    val stringDirective = provide("The cat")
    val intDirective = provide(42)
    val doubleDirective = provide(23.0)
    val symbolDirective = provide('abc)

    val dirStringInt = stringDirective & intDirective
    val dirStringIntDouble = dirStringInt & doubleDirective
    val dirDoubleStringInt = doubleDirective & dirStringInt
    val dirStringIntStringInt = dirStringInt & dirStringInt

    "work for two elements" in {
      Get("/abc") ~> {
        dirStringInt { (str, i) ⇒
          complete(s"$str ${i + 1}")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 43" }
    }
    "work for 2 + 1" in {
      Get("/abc") ~> {
        dirStringIntDouble { (str, i, d) ⇒
          complete(s"$str ${i + 1} ${d + 0.1}")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 43 23.1" }
    }
    "work for 1 + 2" in {
      Get("/abc") ~> {
        dirDoubleStringInt { (d, str, i) ⇒
          complete(s"$str ${i + 1} ${d + 0.1}")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 43 23.1" }
    }
    "work for 2 + 2" in {
      Get("/abc") ~> {
        dirStringIntStringInt { (str, i, str2, i2) ⇒
          complete(s"$str ${i + i2} $str2")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 84 The cat" }
    }
  }
  "Case class extraction with Directive.as" should {
    "extract one argument" in {
      case class MyNumber(i: Int)

      val abcPath = path("abc" / IntNumber).as(MyNumber)(echoComplete)

      Get("/abc/5") ~> abcPath ~> check {
        responseAs[String] shouldEqual "MyNumber(5)"
      }
    }
    "extract two arguments" in {
      case class Person(name: String, age: Int)

      val personPath = path("person" / Segment / IntNumber).as(Person)(echoComplete)

      Get("/person/john/38") ~> personPath ~> check {
        responseAs[String] shouldEqual "Person(john,38)"
      }
    }
  }
  "Dynamic execution of inner routes of Directive0" should {
    "re-execute inner routes every time" in {
      var a = ""
      val dynamicRoute = get { a += "x"; complete(a) }
      def expect(route: Route, s: String) = Get() ~> route ~> check { responseAs[String] shouldEqual s }

      expect(dynamicRoute, "x")
      expect(dynamicRoute, "xx")
      expect(dynamicRoute, "xxx")
      expect(dynamicRoute, "xxxx")
    }
  }
}
