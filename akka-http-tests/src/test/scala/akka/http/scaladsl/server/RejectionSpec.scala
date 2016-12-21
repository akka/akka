/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.server

import scala.collection.JavaConverters._

class RejectionSpec extends RoutingSpec {

  "The Transformation Rejection" should {

    "map to and from Java" in {
      import akka.http.javadsl.{ server ⇒ jserver }
      val rejections = List(RequestEntityExpectedRejection)
      val jrejections: java.lang.Iterable[jserver.Rejection] =
        rejections.map(_.asInstanceOf[jserver.Rejection]).asJava
      val jresult = TransformationRejection(identity).getTransform.apply(jrejections)

      val result = jresult.asScala.map(r ⇒ r.asInstanceOf[Rejection])
      result should ===(rejections)
    }
  }

  "RejectionHandler" should {
    import akka.http.scaladsl.model._

    implicit def myRejectionHandler =
      RejectionHandler.default
        .mapRejectionResponse {
          case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) ⇒
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            res.copy(entity = HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))

          case x ⇒ x // pass through all other types of responses
        }

    val route =
      Route.seal(
        path("hello") {
          complete("Hello there")
        }
      )

    "mapRejectionResponse must not affect normal responses" in {
      Get("/hello") ~> route ~> check {
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`text/plain(UTF-8)`)
        responseAs[String] should ===("""Hello there""")
      }
    }
    "mapRejectionResponse should alter rejection response" in {
      Get("/nope") ~> route ~> check {
        status should ===(StatusCodes.NotFound)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[String] should ===("""{"rejection": "The requested resource could not be found."}""")
      }
    }
  }

}
