/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit.scaladsl

import akka.http.scaladsl.common.{ NameReceptacle, NameDefaultReceptacle }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.testkit.{ TestFrameworkInterface, RouteTest }
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller, FromResponseUnmarshaller }
import akka.http.scaladsl.util.FastFuture
import spray.json.{ JsArray, JsValue, JsObject }
import scala.concurrent.duration._
import scala.annotation.tailrec
import spray.json._

import scala.reflect.ClassTag

trait SprayJsonRouteTest extends RouteTest with SprayJsonSupport {
  this: TestFrameworkInterface ⇒

  def responseJson: JsValue = responseAs[String].parseJson

  // TODO use marshalling infra
  def responseJsonArray: JsArray = responseAs[String].parseJson.asInstanceOf[JsArray]

  // TODO use marshalling infra
  def responseJsonObject: JsObject = responseAs[String].parseJson.asJsObject // TODO use marshalling infra

  def responseJsonIsEmpty: Boolean = responseJson match {
    case a: JsArray  ⇒ a.elements.isEmpty
    case o: JsObject ⇒ o.fields.isEmpty
  }

  def responseJsonNonEmpty: Boolean = !responseJsonIsEmpty

  def responseJsonValueAt(key: String): JsValue = responseJsonObject.fields(key)

  def responseJsonValueAt(idx: Int): JsValue = responseJsonArray.elements(idx)

  def jsonValue(jsonSelector: JsonSelector): JsValue =
    jsonSelector.selectFrom(responseJson)

  trait Selection {
    def selectFrom(js: JsValue): JsValue
  }

  final case class ArrayIndexSelection(idx: Int) extends Selection {
    override def selectFrom(js: JsValue): JsValue = js match {
      case a: JsArray ⇒ a.elements(idx)
      case other      ⇒ throw new AssertionError(s"Attepmted array index extraction ([$idx]) on non-array object, was: ${other.getClass}, value: $other")
    }
  }

  final case class ObjectFieldSelection(fieldName: String) extends Selection {
    override def selectFrom(js: JsValue): JsValue = js match {
      case a: JsObject ⇒ a.fields.getOrElse(fieldName, throw new AssertionError(s"""Attempted json field extraction for '$fieldName', yet object did not contain it. Keys are: ${a.fields.keys.mkString(",")}"""))
      case other       ⇒ throw new AssertionError(s"Attepmted array index extraction ('$fieldName') on non-object, was: ${other.getClass}, value: $other")
    }
  }

  implicit final class JsValueAs(val js: JsValue) {
    def as[T: ClassTag](implicit reader: JsonReader[T]): T = reader.read(js)
  }

  final case class JsonSelector(path: List[Selection]) {
    def selectFrom(js: JsValue): JsValue = {
      val selections = path.reverse
      @tailrec def applySelections(js: JsValue, remaining: List[Selection]): JsValue = remaining match {
        case s :: Nil  ⇒ s.selectFrom(js)
        case s :: rest ⇒ applySelections(s.selectFrom(js), rest)
      }
      applySelections(js, selections)
    }

    def /(idx: Int): JsonSelector = copy(ArrayIndexSelection(idx) :: path)
    def /(field: String): JsonSelector = copy(ObjectFieldSelection(field) :: path)
    def /(field: Symbol): JsonSelector = copy(ObjectFieldSelection(field.name) :: path)
  }

  implicit def int2selector(idx: Int): JsonSelector = JsonSelector(ArrayIndexSelection(idx) :: Nil)
  implicit def symbol2selector(s: Symbol): JsonSelector = string2selector(s.name)
  implicit def string2selector(s: String): JsonSelector = JsonSelector(ObjectFieldSelection(s) :: Nil)

}