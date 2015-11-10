/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpResponse, StatusCodes }
import akka.stream.scaladsl.Source
import akka.util.ByteString

class StreamingResponseSpecs extends RoutingSpec {

  "streaming ByteString responses" should {
    "should render empty string if stream was empty" in {
      import StatusCodes._

      val src = Source.empty[ByteString]
      val entity = HttpEntity.Chunked.fromData(ContentTypes.`application/json`, src)
      val response = HttpResponse(status = StatusCodes.OK, entity = entity)
      val route = complete(response)

      Get() ~> route ~> check {
        status should ===(StatusCodes.OK)
        responseAs[String] should === ("")
      }
    }

  }
}