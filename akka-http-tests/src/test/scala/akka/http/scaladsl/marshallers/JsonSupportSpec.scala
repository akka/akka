/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.{ HttpCharsets, HttpEntity, MediaTypes }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.impl.util._
import org.scalatest.{ Matchers, WordSpec }

case class Employee(fname: String, name: String, age: Int, id: Long, boardMember: Boolean) {
  require(!boardMember || age > 40, "Board members must be older than 40")
}

object Employee {
  val simple = Employee("Frank", "Smith", 42, 12345, false)
  val json = """{"fname":"Frank","name":"Smith","age":42,"id":12345,"boardMember":false}"""

  val utf8 = Employee("Fränk", "Çmi√", 42, 12345, false)
  val utf8json =
    """{
      |  "fname": "Fränk",
      |  "name": "Çmi√",
      |  "age": 42,
      |  "id": 12345,
      |  "boardMember": false
      |}""".stripMargin.getBytes(HttpCharsets.`UTF-8`.nioCharset)

  val illegalEmployeeJson = """{"fname":"Little Boy","name":"Smith","age":7,"id":12345,"boardMember":true}"""
}

/** Common infrastructure needed for several json support subprojects */
abstract class JsonSupportSpec extends WordSpec with Matchers with ScalatestRouteTest {
  require(getClass.getSimpleName.endsWith("Spec"))
  // assuming that the classname ends with "Spec"
  def name: String = getClass.getSimpleName.dropRight(4)
  implicit def marshaller: ToEntityMarshaller[Employee]
  implicit def unmarshaller: FromEntityUnmarshaller[Employee]

  "The " + name should {
    "provide unmarshalling support for a case class" in {
      HttpEntity(MediaTypes.`application/json`, Employee.json) should unmarshalToValue(Employee.simple)
    }
    "provide marshalling support for a case class" in {
      val marshalled = marshal(Employee.simple)

      marshalled.data.utf8String shouldEqual
        """{
          |  "age": 42,
          |  "boardMember": false,
          |  "fname": "Frank",
          |  "id": 12345,
          |  "name": "Smith"
          |}""".stripMarginWithNewline("\n")
    }
    "use UTF-8 as the default charset for JSON source decoding" in {
      HttpEntity(MediaTypes.`application/json`, Employee.utf8json) should unmarshalToValue(Employee.utf8)
    }
    "provide proper error messages for requirement errors" in {
      val result = unmarshal(HttpEntity(MediaTypes.`application/json`, Employee.illegalEmployeeJson))

      result.isFailure shouldEqual true
      val ex = result.failed.get
      ex.getMessage shouldEqual "requirement failed: Board members must be older than 40"
    }
  }
}