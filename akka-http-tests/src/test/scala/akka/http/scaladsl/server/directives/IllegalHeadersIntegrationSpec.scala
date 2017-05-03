/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{ Accept, RawHeader }
import akka.http.scaladsl.server.IntegrationRoutingSpec

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Has to excercise the entire stack, tgus an IntegrationRoutingSpec (not reproducable using just RouteTest).
 */
class IllegalHeadersIntegrationSpec extends IntegrationRoutingSpec {

  "Illegal content type in request" should {
    val route = extractRequest { req ⇒
      complete(s"Accept:${req.header[Accept]}, byName:${req.headers.find(_.is("accept"))}")
    }

    // see: https://github.com/akka/akka-http/issues/1072
    "not StackOverflow but be rejected properly" in {
      val theIllegalHeader = RawHeader("Accept", "*/xml")
      Get().addHeader(theIllegalHeader) ~!> route ~!> { response ⇒
        import response._

        status should ===(StatusCodes.OK)
        val responseString = Await.result(response.entity.toStrict(1.second), 2.seconds).data.utf8String
        responseString should ===("Accept:None, byName:Some(accept: */xml)")
      }
    }
  }

}
