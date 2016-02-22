/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpResponse, StatusCodes }
import akka.stream.scaladsl.Source
import akka.util.ByteString

class StreamingResponseSpecs extends RoutingSpec {

  "streaming ByteString responses" should {
    "should render empty string if stream was empty" in {

      val src = Source.empty[ByteString]
      val entity = HttpEntity.Chunked.fromData(ContentTypes.`application/json`, src)
      val response = HttpResponse(status = StatusCodes.OK, entity = entity)
      val route = complete(response)

      Get() ~> route ~> check {
        status should ===(StatusCodes.OK)
        responseAs[String] should ===("")
      }
    }

  }
}
