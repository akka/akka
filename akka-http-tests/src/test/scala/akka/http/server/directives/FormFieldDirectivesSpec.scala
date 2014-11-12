/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.common.StrictForm
import akka.http.marshallers.xml.ScalaXmlSupport
import akka.http.unmarshalling.Unmarshaller.HexInt
import akka.http.model._
import MediaTypes._

class FormFieldDirectivesSpec extends RoutingSpec {
  implicit val nodeSeqUnmarshaller =
    ScalaXmlSupport.nodeSeqUnmarshaller(`text/xml`, `text/html`, `text/plain`)

  val nodeSeq: xml.NodeSeq = <b>yes</b>
  val urlEncodedForm = FormData(Map("firstName" -> "Mike", "age" -> "42"))
  val urlEncodedFormWithVip = FormData(Map("firstName" -> "Mike", "age" -> "42", "VIP" -> "true", "super" -> "<b>no</b>"))
  val multipartForm = Multipart.FormData {
    Map(
      "firstName" -> HttpEntity("Mike"),
      "age" -> HttpEntity(`text/xml`, "<int>42</int>"),
      "VIPBoolean" -> HttpEntity("true"))
  }
  val multipartFormWithTextHtml = Multipart.FormData {
    Map(
      "firstName" -> HttpEntity("Mike"),
      "age" -> HttpEntity(`text/xml`, "<int>42</int>"),
      "VIP" -> HttpEntity(`text/html`, "<b>yes</b>"),
      "super" -> HttpEntity("no"))
  }
  val multipartFormWithFile = Multipart.FormData(
    Multipart.FormData.BodyPart.Strict("file", HttpEntity(MediaTypes.`text/xml`, "<int>42</int>"),
      Map("filename" -> "age.xml")))

  "The 'formFields' extraction directive" should {
    "properly extract the value of www-urlencoded form fields" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName, "age".as[Int], 'sex?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike42Nonefalse" }
    }
    "properly extract the value of www-urlencoded form fields when an explicit unmarshaller is given" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName, "age".as(HexInt), 'sex?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike66Nonefalse" }
    }
    "properly extract the value of multipart form fields" in {
      Post("/", multipartForm) ~> {
        formFields('firstName, "age", 'sex?, "VIP" ? nodeSeq) { (firstName, age, sex, vip) ⇒
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
          formFields('firstName, "age", 'sex?, "VIPBoolean" ? false) { (firstName, age, sex, vip) ⇒
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
        formFields('firstName, "age", 'sex?, "super" ? nodeSeq) { (firstName, age, sex, vip) ⇒
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
        formFields('name ! "Mr. Mike") { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName ! "Pete") { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Post("/", urlEncodedForm) ~> {
        formFields('firstName ! "Mike") { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The 'formField' requirement with explicit unmarshaller directive" should {
    "block requests that do not contain the required formField" in {
      Post("/", urlEncodedForm) ~> {
        formFields('oldAge.as(HexInt) ! 78) { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Post("/", urlEncodedForm) ~> {
        formFields('age.as(HexInt) ! 78) { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Post("/", urlEncodedForm) ~> {
        formFields('age.as(HexInt) ! 66 /* hex! */ ) { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

}
