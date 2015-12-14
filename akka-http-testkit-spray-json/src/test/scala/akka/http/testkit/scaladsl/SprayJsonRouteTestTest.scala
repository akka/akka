/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit.scaladsl

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.testkit.TestFrameworkInterface
import org.scalatest.{ Matchers, WordSpec }
import spray.json.{ JsArray, JsNumber, JsObject, JsString }
import spray.json._

class SprayJsonRouteTestTest extends WordSpec with Matchers with Directives
  with SprayJsonRouteTest
  with SprayJsonRouteScalaTest
  with DefaultJsonProtocol
  with TestFrameworkInterface.Scalatest {

  import SprayJsonRouteTestTest._
  implicit val personFormat = jsonFormat2(Person)

  val route =
    complete {
      """
        {
          "top": "Top",
          "stringArray": ["0", "1", "2"],
          "intArray": [0, 1, 2],
          "deeper": {
            "inside": 42,
            "anotherInside": "AnotherInside"
          },
          "nestedPerson": {
            "name": "Kapi",
            "age": 42
          }
        }
      """.parseJson
    }

  "SprayJsonRouteTest" should {
    "#jsonValue should introspect json and equal JsString/JsNumber/..." in {
      Get("/") ~> route ~> check {
        jsonValue('top) should ===(JsString("Top"))
        jsonValue("stringArray") should ===(JsArray(JsString("0"), JsString("1"), JsString("2")))
        jsonValue("stringArray" / 1) should ===(JsString("1"))
        jsonValue("intArray") should ===(JsArray(JsNumber(0), JsNumber(1), JsNumber(2)))
        jsonValue("intArray" / 0) should ===(JsNumber(0))
        jsonValue('deeper / "inside") should ===(JsNumber(42))
        jsonValue("deeper" / 'inside) should ===(JsNumber(42))
        jsonValue('deeper / "anotherInside") should ===(JsString("AnotherInside"))
      }
    }
    "#jsonValue should introspect json and equal String/Number/..." in {
      Get("/") ~> route ~> check {
        jsonValue('top) should ===("Top")
        jsonValue('deeper / "inside") should ===(42)
        jsonValue("deeper" / 'inside) should ===(42)
        jsonValue('deeper / "anotherInside") should ===("AnotherInside")
      }
    }
    "be able to unmarshal deep value from #jsonValue" in {
      Get("/") ~> route ~> check {
        jsonValue('nestedPerson).as[Person] should ===(Person("Kapi", 42))
      }
    }
  }
}

object SprayJsonRouteTestTest {
  final case class Person(name: String, age: Int)
}