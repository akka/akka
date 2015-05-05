/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.playjson

import java.lang.StringBuilder

import akka.http.scaladsl.marshallers.{ JsonSupportSpec, Employee }
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller

import play.api.libs.json._
import play.api.libs.functional.syntax._

import com.fasterxml.jackson.databind._

class PlayJsonSupportSpec extends JsonSupportSpec {
  object EmployeeJsonProtocol {

    implicit val employeeFormat: Format[Employee] = (
      (__ \ "fname").format[String] and
      (__ \ "name").format[String] and
      (__ \ "age").format[Int] and
      (__ \ "id").format[Long] and
      (__ \ "boardMember").format[Boolean])(Employee.apply, unlift(Employee.unapply))

  }
  import EmployeeJsonProtocol._

  implicit val printer: PlayJsonSupport.Printer = (value: JsValue) ⇒ {
    val mapper = (new ObjectMapper()).configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    val node = mapper.readTree(Json.stringify(value))
    val json = mapper.treeToValue(node, classOf[java.util.Map[_, _]])
    val result = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
    result.replace(" : ", ": ")
  }

  implicit def marshaller: ToEntityMarshaller[Employee] = PlayJsonSupport.playJsonMarshaller[Employee]
  implicit def unmarshaller: FromEntityUnmarshaller[Employee] = PlayJsonSupport.playJsonUnmarshaller[Employee]
}
