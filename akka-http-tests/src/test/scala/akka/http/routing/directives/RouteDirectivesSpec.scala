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

import scala.concurrent.{ Future, Promise }
import akka.http.model._
import akka.http.util._
import headers._
import StatusCodes._
import MediaTypes._
import akka.http.marshalling._
import akka.actor.ActorRef
import akka.http.routing._

class RouteDirectivesSpec extends RoutingSpec {

  "The `complete` directive" should {
    "by chainable with the `&` operator" in pendingUntilFixed {
      Get() ~> (get & complete("yeah")) ~> check { responseAs[String] mustEqual "yeah" }
    }
    "allow for factoring out a StandardRoute" in pendingUntilFixed {
      Get() ~> (get & complete)("yeah") ~> check { responseAs[String] mustEqual "yeah" }
    }
    "be lazy in its argument evaluation, independently of application style" in pendingUntilFixed {
      var i = 0
      Put() ~> {
        get { complete { i += 1; "get" } } ~
          put { complete { i += 1; "put" } } ~
          (post & complete { i += 1; "post" }) ~
          (delete & complete) { i += 1; "delete" }
      } ~> check {
        responseAs[String] mustEqual "put"
        i mustEqual 1
      }
    }
    "support completion from response futures" in pendingUntilFixed {
      "simple case without marshaller" in pendingUntilFixed {
        Get() ~> {
          get & complete(Promise.successful(HttpResponse(entity = "yup")).future)
        } ~> check { responseAs[String] mustEqual "yup" }
      }
      "for successful futures and marshalling" in pendingUntilFixed {
        Get() ~> complete(Promise.successful("yes").future) ~> check { responseAs[String] mustEqual "yes" }
      }
      "for failed futures and marshalling" in pendingUntilFixed {
        object TestException extends RuntimeException
        Get() ~> complete(Promise.failed[String](TestException).future) ~>
          check {
            status mustEqual StatusCodes.InternalServerError
            responseAs[String] mustEqual "There was an internal server error."
          }
      }
      "for futures failed with a RejectionError" in pendingUntilFixed {
        Get() ~> complete(Promise.failed[String](RejectionError(AuthorizationFailedRejection)).future) ~>
          check {
            rejection mustEqual AuthorizationFailedRejection
          }
      }
    }
    "allow easy handling of futured ToResponseMarshallers" in pending /*{
      trait RegistrationStatus
      case class Registered(name: String) extends RegistrationStatus
      case object AlreadyRegistered extends RegistrationStatus

      val route =
        get {
          path("register" / Segment) { name ⇒
            def registerUser(name: String): Future[RegistrationStatus] = Future.successful {
              name match {
                case "otto" ⇒ AlreadyRegistered
                case _      ⇒ Registered(name)
              }
            }
            complete {
              registerUser(name).map[ToResponseMarshallable] {
                case Registered(_) ⇒ HttpEntity.Empty
                case AlreadyRegistered ⇒
                  import spray.json.DefaultJsonProtocol._
                  import spray.httpx.SprayJsonSupport._
                  (StatusCodes.BadRequest, Map("error" -> "User already Registered"))
              }
            }
          }
        }

      Get("/register/otto") ~> route ~> check {
        status mustEqual StatusCodes.BadRequest
      }
      Get("/register/karl") ~> route ~> check {
        status mustEqual StatusCodes.OK
        entity mustEqual HttpEntity.Empty
      }
    }*/
    "do Content-Type negotiation for multi-marshallers" in pendingUntilFixed {
      val route = get & complete(Data("Ida", 83))

      import akka.http.model.headers.Accept
      Get().withHeaders(Accept(MediaTypes.`application/json`)) ~> route ~> check {
        responseAs[String] mustEqual
          """{
            |  "name": "Ida",
            |  "age": 83
            |}""".stripMarginWithNewline("\n")
      }
      Get().withHeaders(Accept(MediaTypes.`text/xml`)) ~> route ~> check {
        responseAs[xml.NodeSeq] mustEqual <data><name>Ida</name><age>83</age></data>
      }
      pending
      /*Get().withHeaders(Accept(MediaTypes.`text/plain`)) ~> HttpService.sealRoute(route) ~> check {
        status mustEqual StatusCodes.NotAcceptable
      }*/
    }
  }

  "the redirect directive" should {
    "produce proper 'Found' redirections" in pendingUntilFixed {
      Get() ~> {
        redirect("/foo", Found)
      } ~> check {
        response mustEqual HttpResponse(
          status = 302,
          entity = HttpEntity(`text/html`, "The requested resource temporarily resides under <a href=\"/foo\">this URI</a>."),
          headers = Location("/foo") :: Nil)
      }
    }
    "produce proper 'NotModified' redirections" in pendingUntilFixed {
      Get() ~> {
        redirect("/foo", NotModified)
      } ~> check { response mustEqual HttpResponse(304, headers = Location("/foo") :: Nil) }
    }
  }

  case class Data(name: String, age: Int)
  object Data {
    //import spray.json.DefaultJsonProtocol._
    //import spray.httpx.SprayJsonSupport._

    val jsonMarshaller: Marshaller[Data] = FIXME // jsonFormat2(Data.apply)
    val xmlMarshaller: Marshaller[Data] = FIXME
    /*Marshaller.delegate[Data, xml.NodeSeq](MediaTypes.`text/xml`) { (data: Data) ⇒
        <data><name>{ data.name }</name><age>{ data.age }</age></data>
      }*/

    implicit val dataMarshaller: ToResponseMarshaller[Data] = FIXME
    //ToResponseMarshaller.oneOf(MediaTypes.`application/json`, MediaTypes.`text/xml`)(jsonMarshaller, xmlMarshaller)
  }
}
