/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.common.StrictForm
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.unmarshalling.Unmarshaller.HexInt
import akka.http.scaladsl.model._
import MediaTypes._

class FormFieldDirectivesSpec extends RoutingSpec {
  // FIXME: unfortunately, it has make a come back, this time it's reproducible ...
  import akka.http.scaladsl.server.directives.FormFieldDirectives.FieldMagnet

  implicit val nodeSeqUnmarshaller =
    ScalaXmlSupport.nodeSeqUnmarshaller(`text/xml`, `text/html`, `text/plain`)

  val nodeSeq: xml.NodeSeq = <b>yes</b>
  val urlEncodedForm = FormData(Map("firstName" -> "Mike", "age" -> "42"))
  val urlEncodedFormWithVip = FormData(Map("firstName" -> "Mike", "age" -> "42", "VIP" -> "true", "super" -> "<b>no</b>"))
  val multipartForm = Multipart.FormData {
    Map(
      "firstName" -> HttpEntity("Mike"),
      "age" -> HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>"),
      "VIPBoolean" -> HttpEntity("true"))
  }
  val multipartFormWithTextHtml = Multipart.FormData {
    Map(
      "firstName" -> HttpEntity("Mike"),
      "age" -> HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>"),
      "VIP" -> HttpEntity(ContentTypes.`text/html(UTF-8)`, "<b>yes</b>"),
      "super" -> HttpEntity("no"))
  }
  val multipartFormWithFile = Multipart.FormData(
    Multipart.FormData.BodyPart.Strict("file", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>"),
      Map("filename" -> "age.xml")))

  "The 'formFields' extraction directive" should {
    "properly extract the value of www-urlencoded form fields" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName, "age".as[Int], 'sex.?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike42Nonefalse" }
    }
    "properly extract the value of www-urlencoded form fields when an explicit unmarshaller is given" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName, "age".as(HexInt), 'sex.?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike66Nonefalse" }
    }
    "properly extract the value of multipart form fields" in {
      Post("/", multipartForm) ~> {
        formFields('firstName, "age", 'sex.?, "VIP" ? nodeSeq) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike<int>42</int>None<b>yes</b>" }
    }
    "extract StrictForm.FileData from a multipart part" in {
      Post("/", multipartFormWithFile) ~> {
        formFields('file.as[StrictForm.FileData]) {
          case StrictForm.FileData(name, HttpEntity.Strict(ct, data)) ⇒
            complete(s"type ${ct.mediaType} length ${data.length} filename ${name.get}")
        }
      } ~> check { responseAs[String] shouldEqual "type text/xml length 13 filename age.xml" }
    }
    "reject the request with a MissingFormFieldRejection if a required form field is missing" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName, "age", 'sex, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { rejection shouldEqual MissingFormFieldRejection("sex") }
    }
    "properly extract the value if only a urlencoded deserializer is available for a multipart field that comes without a" +
      "Content-Type (or text/plain)" in {
        Post("/", multipartForm) ~> {
          formFields('firstName, "age", 'sex.?, "VIPBoolean" ? false) { (firstName, age, sex, vip) ⇒
            complete(firstName + age + sex + vip)
          }
        } ~> check {
          responseAs[String] shouldEqual "Mike<int>42</int>Nonetrue"
        }
      }
    "work even if only a FromStringUnmarshaller is available for a multipart field with custom Content-Type" in {
      Post("/", multipartFormWithTextHtml) ~> {
        formFields(('firstName, "age", 'super ? false)) { (firstName, age, vip) ⇒
          complete(firstName + age + vip)
        }
      } ~> check {
        responseAs[String] shouldEqual "Mike<int>42</int>false"
      }
    }
    "work even if only a FromEntityUnmarshaller is available for a www-urlencoded field" in {
      Post("/", urlEncodedFormWithVip) ~> {
        formFields('firstName, "age", 'sex.?, "super" ? nodeSeq) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check {
        responseAs[String] shouldEqual "Mike42None<b>no</b>"
      }
    }
  }
  "The 'formField' requirement directive" should {
    "block requests that do not contain the required formField" in {
      Post("/", urlEncodedForm) ~> {
        formField('name ! "Mr. Mike") { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Post("/", urlEncodedForm) ~> {
        formField('firstName ! "Pete") { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Post("/", urlEncodedForm) ~> {
        formField('firstName ! "Mike") { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The 'formField' requirement with explicit unmarshaller directive" should {
    "block requests that do not contain the required formField" in {
      Post("/", urlEncodedForm) ~> {
        formField('oldAge.as(HexInt) ! 78) { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Post("/", urlEncodedForm) ~> {
        formField('age.as(HexInt) ! 78) { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Post("/", urlEncodedForm) ~> {
        formField('age.as(HexInt) ! 66 /* hex! */ ) { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The 'formField' repeated directive" should {
    "extract an empty Iterable when the parameter is absent" in {
      Post("/", FormData("age" -> "42")) ~> {
        formField('hobby.*) { echoComplete }
      } ~> check { responseAs[String] === "List()" }
    }
    "extract all occurrences into an Iterable when parameter is present" in {
      Post("/", FormData("age" -> "42", "hobby" -> "cooking", "hobby" -> "reading")) ~> {
        formField('hobby.*) { echoComplete }
      } ~> check { responseAs[String] === "List(cooking, reading)" }
    }
    "extract as Iterable[Int]" in {
      Post("/", FormData("age" -> "42", "number" -> "3", "number" -> "5")) ~> {
        formField('number.as[Int].*) { echoComplete }
      } ~> check { responseAs[String] === "List(3, 5)" }
    }
    "extract as Iterable[Int] with an explicit deserializer" in {
      Post("/", FormData("age" -> "42", "number" -> "3", "number" -> "A")) ~> {
        formField('number.as(HexInt).*) { echoComplete }
      } ~> check { responseAs[String] === "List(3, 10)" }
    }
  }

  "The 'formFieldMap' directive" should {
    "extract fields with different keys" in {
      Post("/", FormData("age" -> "42", "numberA" -> "3", "numberB" -> "5")) ~> {
        formFieldMap { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Map(age -> 42, numberA -> 3, numberB -> 5)" }
    }
  }

  "The 'formFieldSeq' directive" should {
    "extract all fields" in {
      Post("/", FormData("age" -> "42", "number" -> "3", "number" -> "5")) ~> {
        formFieldSeq { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Vector((age,42), (number,3), (number,5))" }
    }
    "produce empty Seq when FormData is empty" in {
      Post("/", FormData.Empty) ~> {
        formFieldSeq { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Vector()" }
    }
  }

  "The 'formFieldMultiMap' directive" should {
    "extract fields with different keys (with duplicates)" in {
      Post("/", FormData("age" -> "42", "number" -> "3", "number" -> "5")) ~> {
        formFieldMultiMap { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Map(age -> List(42), number -> List(5, 3))" }
    }
  }
}
