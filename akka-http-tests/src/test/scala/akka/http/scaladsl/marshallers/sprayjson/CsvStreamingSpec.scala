/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.http.scaladsl.marshallers.Employee
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.io.Framing
import akka.util.ByteString
import org.scalatest.{ Matchers, WordSpec }

class CsvStreamingSpec extends WordSpec with Matchers with ScalatestRouteTest
  with Directives {

  //#marshalling-and-framing
  val MaxFrameSize = Int.MaxValue

  // a CSV is framed by newlines:
  val framing = Framing.delimiter(ByteString("\n"), MaxFrameSize, allowTruncation = true)
  implicit val fct = FramingWithContentType(framing, ContentTypes.`text/plain`)

  // simple example (un)marshallers from/to an Employee:
  implicit def marshaller: Marshaller[Employee, ByteString] = Marshaller.strict[Employee, ByteString] { employee ⇒
    Marshalling.Opaque(() ⇒ ByteString(List(employee.name, employee.id).mkString(",")))
  }
  implicit def unmarshaller: Unmarshaller[ByteString, Employee] = Unmarshaller.strict { line ⇒
    val parts = line.utf8String.split(",")

    Employee.simple.copy(name = parts(0), age = parts(1).toInt)
  }
  //#marshalling-and-framing

  val PostCsv = (path: String, data: String) ⇒
    Post(path).withEntity(ContentTypes.`text/plain`, data)

  val PostJson = (path: String, data: String) ⇒
    Post(path).withEntity(ContentTypes.`application/json`, data)

  val lineByLine =
    """|Frank,42
       |Bob,99""".stripMargin

  // TODO could be made available pre-packaged, now it's a PoC that it's possible to use a different framing -- ktoso
  "Example CsvStreaming" should {
    "read using entity(stream[T])" in {
      val route = post {
        entity(stream[Employee]) { employees ⇒
          complete(employees.map(_.name).intersperse(",").runFold("")(_ + _))
        }
      }

      PostCsv("/", lineByLine) ~> route ~> check {
        responseAs[String] shouldEqual "Frank,Bob"
      }
    }
    "reject POST with Content-Type `application/json`" in {
      val route = post {
        entity(stream[Employee]) { employees ⇒
          complete(employees.map(_.name).intersperse(",").runFold("")(_ + _))
        }
      }

      PostJson("/", lineByLine) ~> route ~> check {
        handled shouldEqual false
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(ContentTypes.`text/plain`))
      }
    }
  }
}