/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import org.scalatest.FreeSpec
import scala.concurrent.{ Future, Promise }
import akka.testkit.EventFilter
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._
import headers._
import StatusCodes._

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
      "for failed futures and marshalling" in EventFilter.error(
        occurrences = 1,
        message = "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response."
      ).intercept {
        object TestException extends RuntimeException("Boom")
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
    "allow easy handling of futured ToResponseMarshallers" in {
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
                  import SprayJsonSupport._
                  StatusCodes.BadRequest → Map("error" → "User already Registered")
              }
            }
          }
        }

      Get("/register/otto") ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
      Get("/register/karl") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual ""
      }
    }
    "do Content-Type negotiation for multi-marshallers" in {
      val route = get & complete(Data("Ida", 83))

      import akka.http.scaladsl.model.headers.Accept
      Get().withHeaders(Accept(MediaTypes.`application/json`)) ~> route ~> check {
        responseAs[String] shouldEqual
          """{"name":"Ida","age":83}"""
      }
      Get().withHeaders(Accept(MediaTypes.`text/xml`)) ~> route ~> check {
        responseAs[xml.NodeSeq] shouldEqual <data><name>Ida</name><age>83</age></data>
      }
      Get().withHeaders(Accept(MediaTypes.`text/plain`)) ~> Route.seal(route) ~> check {
        status shouldEqual StatusCodes.NotAcceptable
      }
    }
  }

  "the redirect directive should" - {
    "produce proper 'Found' redirections" in {
      Get() ~> {
        redirect("/foo", Found)
      } ~> check {
        response shouldEqual HttpResponse(
          status = 302,
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            "The requested resource temporarily resides under <a href=\"/foo\">this URI</a>."),
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
    import spray.json.DefaultJsonProtocol._
    import SprayJsonSupport._
    import ScalaXmlSupport._

    val jsonMarshaller: ToEntityMarshaller[Data] = jsonFormat2(Data.apply)

    val xmlMarshaller: ToEntityMarshaller[Data] = Marshaller.combined { (data: Data) ⇒
      <data><name>{ data.name }</name><age>{ data.age }</age></data>
    }

    implicit val dataMarshaller: ToResponseMarshaller[Data] =
      Marshaller.oneOf(jsonMarshaller, xmlMarshaller)
  }
}
