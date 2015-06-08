/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model

import org.scalatest.{ FreeSpec, MustMatchers }

import scala.collection.JavaConverters._

class JavaApiSpec extends FreeSpec with MustMatchers {
  "The Java API should work for" - {
    "work with Uris" - {
      "addParameter" in {
        Uri.create("/abc")
          .addParameter("name", "paul") must be(Uri.create("/abc?name=paul"))
      }
      "addSegment" in {
        Uri.create("/abc")
          .addPathSegment("def") must be(Uri.create("/abc/def"))

        Uri.create("/abc/")
          .addPathSegment("def") must be(Uri.create("/abc/def"))
      }
      "scheme/host/port" in {
        Uri.create("/abc")
          .scheme("http")
          .host("example.com")
          .port(8258) must be(Uri.create("http://example.com:8258/abc"))
      }
      "toRelative" in {
        Uri.create("http://example.com/abc")
          .toRelative must be(Uri.create("/abc"))
      }
      "pathSegments" in {
        Uri.create("/abc/def/ghi/jkl")
          .pathSegments().asScala.toSeq must contain inOrderOnly ("abc", "def", "ghi", "jkl")
      }
      "access parameterMap" in {
        Uri.create("/abc?name=blub&age=28")
          .parameterMap().asScala must contain allOf ("name" -> "blub", "age" -> "28")
      }
      "access parameters" in {
        val Seq(param1, param2, param3) =
          Uri.create("/abc?name=blub&age=28&name=blub2")
            .parameters.asScala.toSeq

        param1.getKey must be("name")
        param1.getValue must be("blub")

        param2.getKey must be("age")
        param2.getValue must be("28")

        param3.getKey must be("name")
        param3.getValue must be("blub2")
      }
      "containsParameter" in {
        val uri = Uri.create("/abc?name=blub")
        uri.containsParameter("name") must be(true)
        uri.containsParameter("age") must be(false)
      }
      "access single parameter" in {
        val uri = Uri.create("/abc?name=blub")
        uri.parameter("name") must be(akka.japi.Option.some("blub"))
        uri.parameter("age") must be(akka.japi.Option.none)

        Uri.create("/abc?name=blub&name=blib").parameter("name") must be(akka.japi.Option.some("blub"))
      }
    }
  }
}
