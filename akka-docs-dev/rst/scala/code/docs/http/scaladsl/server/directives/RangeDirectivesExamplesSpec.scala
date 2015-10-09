///*
// * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
// */
//
//package docs.http.scaladsl.server
//package directives
//
//import akka.http.javadsl.model.headers.RangeUnit
//import akka.http.scaladsl.model.Multipart.ByteRanges.BodyPart
//import akka.http.scaladsl.model._
//import headers._
//
//class RangeDirectivesExamplesSpec extends RoutingSpec {
//
//  "withRangeSupport" in {
//    val route =
//      withRangeSupport {
//        complete("ABCDEFGH")
//      }
//
//    Get() ~> addHeader(Range(ByteRange(3, 4))) ~> route ~> check {
//      headers should contain(`Content-Range`(ContentRange(3, 4, 8)))
//      status shouldEqual StatusCodes.PartialContent
//      responseAs[String] shouldEqual "DE"
//    }
//
//    Get() ~> addHeader(Range(ByteRange(0, 1), ByteRange(1, 2), ByteRange(6, 7))) ~> route ~> check {
//      headers must not(contain(like[HttpHeader] { case `Content-Range`(_, _) ⇒ ok }))
//      responseAs[MultipartByteRanges] must beLike {
//        case MultipartByteRanges(
//          BodyPart(entity1, `Content-Range`(RangeUnits.Bytes, range1) +: _) +:
//            BodyPart(entity2, `Content-Range`(RangeUnits.Bytes, range2) +: _) +: Seq()
//          ) ⇒ entity1.asString shouldEqual "ABC" and range1 shouldEqual ContentRange(0, 2, 8) and
//          entity2.asString shouldEqual "GH" and range2 shouldEqual ContentRange(6, 7, 8)
//      }
//    }
//  }
//}
