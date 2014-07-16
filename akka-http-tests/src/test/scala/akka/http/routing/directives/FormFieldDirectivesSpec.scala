/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.shapeless.HNil
import akka.http.model._
import headers._
import akka.http.marshalling._
import akka.http.unmarshalling._
import akka.http.routing._

class FormFieldDirectivesSpec extends RoutingSpec {

  val nodeSeq: xml.NodeSeq = <b>yes</b>
  val urlEncodedForm = FormData(Map("firstName" -> "Mike", "age" -> "42"))
  val urlEncodedFormWithVip = FormData(Map("firstName" -> "Mike", "age" -> "42", "VIP" -> "true"))
  val multipartForm = MultipartFormData {
    Map(
      "firstName" -> BodyPart("Mike"),
      "age" -> BodyPart(marshalUnsafe(<int>42</int>)),
      "VIPBoolean" -> BodyPart("true"))
  }
  val multipartFormWithTextHtml = MultipartFormData {
    Map(
      "firstName" -> BodyPart("Mike"),
      "age" -> BodyPart(marshalUnsafe(<int>42</int>)),
      "VIP" -> BodyPart(HttpEntity(MediaTypes.`text/html`, "<b>yes</b>")))
  }
  "tests" should {
    "be resurrected" in pending
  }
  /*val multipartFormWithFile = MultipartFormData(Seq(
    BodyPart(marshalUnsafe(<int>42</int>), Seq(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("filename" -> "age.xml", "name" -> "file"))))))

  "The 'formFields' extraction directive" should {
    "properly extract the value of www-urlencoded form fields (using the general HList-based variant)" in {
      Get("/", urlEncodedForm) ~> {
        formFields('firstName :: ("age".as[Int]) :: ('sex?) :: ("VIP" ? false) :: HNil) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] mustEqual "Mike42Nonefalse" }
    }
    "properly extract the value of www-urlencoded form fields (using the simple non-HList-based variant)" in {
      Get("/", urlEncodedForm) ~> {
        formFields('firstName, "age".as[Int], 'sex?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] mustEqual "Mike42Nonefalse" }
    }
    "properly extract the value of www-urlencoded form fields when an explicit deserializer is given" in {
      Get("/", urlEncodedForm) ~> {
        formFields('firstName, "age".as(HexInt), 'sex?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] mustEqual "Mike66Nonefalse" }
    }
    "properly extract the value of multipart form fields (using the general HList-based variant)" in {
      Get("/", multipartForm) ~> {
        formFields('firstName :: "age" :: ('sex?) :: ("VIP" ? nodeSeq) :: HNil) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] mustEqual "Mike<int>42</int>None<b>yes</b>" }
    }
    "properly extract the value of multipart form fields (using the simple non-HList-based variant)" in {
      Get("/", multipartForm) ~> {
        formFields('firstName, "age", 'sex?, "VIP" ? nodeSeq) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] mustEqual "Mike<int>42</int>None<b>yes</b>" }
    }
    "extract an uploaded FormFile from multipart form fields" in {
      Get("/", multipartFormWithFile) ~> {
        formFields('file.as[FormFile]) { file ⇒
          complete(s"type ${file.entity.contentType.mediaType} length ${file.entity.data.length} filename ${file.name.get}")
        }
      } ~> check { responseAs[String] mustEqual "type text/xml length 13 filename age.xml" }
    }
    "reject the request with a MissingFormFieldRejection if a required form field is missing" in {
      Get("/", urlEncodedForm) ~> {
        formFields('firstName, "age", 'sex, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { rejection mustEqual MissingFormFieldRejection("sex") }
    }
    "properly extract the value if only a urlencoded deserializer is available for a multipart field that comes without a" +
      "Content-Type (or text/plain)" in {
        Get("/", multipartForm) ~> {
          formFields('firstName, "age", 'sex?, "VIPBoolean" ? false) { (firstName, age, sex, vip) ⇒
            complete(firstName + age + sex + vip)
          }
        } ~> check {
          responseAs[String] mustEqual "Mike<int>42</int>Nonetrue"
        }
      }
    "create a proper error message if only a urlencoded deserializer is available for a multipart field with custom " +
      "Content-Type" in {
        Get("/", multipartFormWithTextHtml) ~> {
          formFields(('firstName, "age", 'VIP ? false)) { (firstName, age, vip) ⇒
            complete(firstName + age + vip)
          }
        } ~> check {
          rejection mustEqual UnsupportedRequestContentTypeRejection("Field 'VIP' can only be read from " +
            "'application/x-www-form-urlencoded' form content but was 'text/html'")
        }
      }
    "create a proper error message if only a multipart unmarshaller is available for a www-urlencoded field" in {
      Get("/", urlEncodedFormWithVip) ~> {
        formFields('firstName, "age", 'sex?, "VIP" ? nodeSeq) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check {
        rejection mustEqual UnsupportedRequestContentTypeRejection("Expected 'text/xml' or 'application/xml' or 'text/html' " +
          "or 'application/xhtml+xml' but tried to read from application/x-www-form-urlencoded encoded " +
          "field 'VIP' which provides only text/plain values.")
      }
    }
  }
  "The 'formField' requirement directive" should {
    "block requests that do not contain the required formField" in {
      Get("/", urlEncodedForm) ~> {
        formFields('name ! "Mr. Mike") { completeOk }
      } ~> check { handled mustEqual (false) }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Get("/", urlEncodedForm) ~> {
        formFields('firstName ! "Pete") { completeOk }
      } ~> check { handled mustEqual (false) }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Get("/", urlEncodedForm) ~> {
        formFields('firstName ! "Mike") { completeOk }
      } ~> check { response mustEqual Ok }
    }
  }
  "The 'formField' requirement with deserializer directive" should {
    "block requests that do not contain the required formField" in {
      Get("/", urlEncodedForm) ~> {
        formFields('oldAge.as(Deserializer.HexInt) ! 78) { completeOk }
      } ~> check { handled mustEqual (false) }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Get("/", urlEncodedForm) ~> {
        formFields('age.as(Deserializer.HexInt) ! 78) { completeOk }
      } ~> check { handled mustEqual (false) }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Get("/", urlEncodedForm) ~> {
        formFields('age.as(Deserializer.HexInt) ! 66 /* hex! */ ) { completeOk }
      } ~> check { response mustEqual Ok }
    }
  }*/
}
