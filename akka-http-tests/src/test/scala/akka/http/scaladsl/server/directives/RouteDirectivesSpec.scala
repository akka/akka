/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server.directives

import org.scalatest.FreeSpec

import scala.concurrent.Promise
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import headers._
import StatusCodes._
import MediaTypes._

class RouteDirectivesSpec extends FreeSpec with GenericRoutingSpec {

  "The `complete` directive should" - {
    "by chainable with the `&` operator" in {
      Get() ~> (get & complete("yeah")) ~> check { responseAs[String] shouldEqual "yeah" }
    }
    "be lazy in its argument evaluation, independently of application style" in {
      var i = 0
      Put() ~> {
        get { complete { i += 1; "get" } } ~
          put { complete { i += 1; "put" } } ~
          (post & complete { i += 1; "post" })
      } ~> check {
        responseAs[String] shouldEqual "put"
        i shouldEqual 1
      }
    }
    "support completion from response futures" - {
      "simple case without marshaller" in {
        Get() ~> {
          get & complete(Promise.successful(HttpResponse(entity = "yup")).future)
        } ~> check { responseAs[String] shouldEqual "yup" }
      }
      "for successful futures and marshalling" in {
        Get() ~> complete(Promise.successful("yes").future) ~> check { responseAs[String] shouldEqual "yes" }
      }
      "for failed futures and marshalling" in {
        object TestException extends RuntimeException
        Get() ~> complete(Promise.failed[String](TestException).future) ~>
          check {
            status shouldEqual StatusCodes.InternalServerError
            responseAs[String] shouldEqual "There was an internal server error."
          }
      }
      "for futures failed with a RejectionError" in {
        Get() ~> complete(Promise.failed[String](RejectionError(AuthorizationFailedRejection)).future) ~>
          check {
            rejection shouldEqual AuthorizationFailedRejection
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
        status shouldEqual StatusCodes.BadRequest
      }
      Get("/register/karl") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        entity shouldEqual HttpEntity.Empty
      }
    }*/
    "do Content-Type negotiation for multi-marshallers" in pendingUntilFixed {
      val route = get & complete(Data("Ida", 83))

      import akka.http.scaladsl.model.headers.Accept
      Get().withHeaders(Accept(MediaTypes.`application/json`)) ~> route ~> check {
        responseAs[String] shouldEqual
          """{
            |  "name": "Ida",
            |  "age": 83
            |}""".stripMarginWithNewline("\n")
      }
      Get().withHeaders(Accept(MediaTypes.`text/xml`)) ~> route ~> check {
        responseAs[xml.NodeSeq] shouldEqual <data><name>Ida</name><age>83</age></data>
      }
      pending
      /*Get().withHeaders(Accept(MediaTypes.`text/plain`)) ~> HttpService.sealRoute(route) ~> check {
        status shouldEqual StatusCodes.NotAcceptable
      }*/
    }
  }

  "the redirect directive should" - {
    "produce proper 'Found' redirections" in {
      Get() ~> {
        redirect("/foo", Found)
      } ~> check {
        response shouldEqual HttpResponse(
          status = 302,
          entity = HttpEntity(`text/html`, "The requested resource temporarily resides under <a href=\"/foo\">this URI</a>."),
          headers = Location("/foo") :: Nil)
      }
    }

    "produce proper 'NotModified' redirections" in {
      Get() ~> {
        redirect("/foo", NotModified)
      } ~> check { response shouldEqual HttpResponse(304, headers = Location("/foo") :: Nil) }
    }
  }

  case class Data(name: String, age: Int)
  object Data {
    //import spray.json.DefaultJsonProtocol._
    //import spray.httpx.SprayJsonSupport._

    val jsonMarshaller: ToEntityMarshaller[Data] = FIXME // jsonFormat2(Data.apply)
    val xmlMarshaller: ToEntityMarshaller[Data] = FIXME
    /*Marshaller.delegate[Data, xml.NodeSeq](MediaTypes.`text/xml`) { (data: Data) ⇒
        <data><name>{ data.name }</name><age>{ data.age }</age></data>
      }*/

    implicit val dataMarshaller: ToResponseMarshaller[Data] = FIXME
    //ToResponseMarshaller.oneOf(MediaTypes.`application/json`, MediaTypes.`text/xml`)(jsonMarshaller, xmlMarshaller)
  }
}
