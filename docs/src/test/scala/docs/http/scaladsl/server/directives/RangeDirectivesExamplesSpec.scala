/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import com.typesafe.config.{ ConfigFactory, Config }

import headers._
import scala.concurrent.Await
import scala.concurrent.duration._
import docs.http.scaladsl.server.RoutingSpec

class RangeDirectivesExamplesSpec extends RoutingSpec {

  override def testConfig: Config =
    ConfigFactory.parseString("akka.http.routing.range-coalescing-threshold=2").withFallback(super.testConfig)

  "withRangeSupport" in {
    //#withRangeSupport
    val route =
      withRangeSupport {
        complete("ABCDEFGH")
      }

    Get() ~> addHeader(Range(ByteRange(3, 4))) ~> route ~> check {
      headers should contain(`Content-Range`(ContentRange(3, 4, 8)))
      status shouldEqual StatusCodes.PartialContent
      responseAs[String] shouldEqual "DE"
    }

    // we set "akka.http.routing.range-coalescing-threshold = 2"
    // above to make sure we get two BodyParts
    Get() ~> addHeader(Range(ByteRange(0, 1), ByteRange(1, 2), ByteRange(6, 7))) ~> route ~> check {
      headers.collectFirst { case `Content-Range`(_, _) => true } shouldBe None
      val responseF = responseAs[Multipart.ByteRanges].parts
        .runFold[List[Multipart.ByteRanges.BodyPart]](Nil)((acc, curr) => curr :: acc)

      val response = Await.result(responseF, 3.seconds).reverse

      response should have length 2

      val part1 = response(0)
      part1.contentRange === ContentRange(0, 2, 8)
      part1.entity should matchPattern {
        case HttpEntity.Strict(_, bytes) if bytes.utf8String == "ABC" =>
      }

      val part2 = response(1)
      part2.contentRange === ContentRange(6, 7, 8)
      part2.entity should matchPattern {
        case HttpEntity.Strict(_, bytes) if bytes.utf8String == "GH" =>
      }
    }
    //#withRangeSupport
  }

}
