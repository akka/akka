/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit.scaladsl

import akka.http.scaladsl.testkit.TestFrameworkInterface
import org.scalactic.Equality
import spray.json._

import scala.collection.immutable

// TODO we can't depend on scalatest here AFAIR
trait SprayJsonRouteScalaTest extends SprayJsonRouteTest {
  this: TestFrameworkInterface ⇒

  //noinspection SimplifyBoolean
  implicit val jsValueEq = new Equality[JsValue] {
    override def areEqual(_a: JsValue, _b: Any): Boolean = (_a, _b) match {
      case (a: JsString, b: String)          ⇒ a.value == b
      case (a: JsString, b)                  ⇒ a == b

      case (a: JsObject, b: JsObject)        ⇒ a == b
      case (a: JsObject, b)                  ⇒ ???

      case (a: JsNumber, b: JsNumber)        ⇒ a == b
      case (a: JsNumber, b: Int)             ⇒ a.value.toIntExact == b
      case (a: JsNumber, b: Long)            ⇒ a.value.toLongExact == b
      case (a: JsNumber, b: Float)           ⇒ a.value == BigDecimal(b)
      case (a: JsNumber, b: Double)          ⇒ a.value == BigDecimal(b)
      case (a: JsNumber, b)                  ⇒ a == b

      case (a: JsArray, b: JsArray)          ⇒ a == b
      case (a: JsArray, b: immutable.Seq[_]) ⇒ ???
      case (a: JsArray, b: Seq[_])           ⇒ ???
      case (a: JsArray, b)                   ⇒ ???

      case (JsTrue, b: Boolean)              ⇒ true == b
      case (JsTrue, b)                       ⇒ JsTrue == b

      case (JsFalse, b: Boolean)             ⇒ false == b
      case (JsFalse, b)                      ⇒ JsFalse == b

      case (JsNull, JsNull)                  ⇒ true
      case (JsNull, b)                       ⇒ null == b
    }
  }

}
