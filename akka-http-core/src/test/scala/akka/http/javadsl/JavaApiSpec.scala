/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model

import org.scalatest.{ MustMatchers, FreeSpec }
import scala.collection.JavaConverters._

class JavaApiSpec extends FreeSpec with MustMatchers {
  "The Java API should work for" - {
    "work with Uris" - {
      "addParameter" in {
        Accessors.Uri("/abc")
          .addParameter("name", "paul") must be(Accessors.Uri("/abc?name=paul"))
      }
      "addSegment" in {
        Accessors.Uri("/abc")
          .addPathSegment("def") must be(Accessors.Uri("/abc/def"))

        Accessors.Uri("/abc/")
          .addPathSegment("def") must be(Accessors.Uri("/abc/def"))
      }
      "scheme/host/port" in {
        Accessors.Uri("/abc")
          .scheme("http")
          .host("example.com")
          .port(8258) must be(Accessors.Uri("http://example.com:8258/abc"))
      }
      "toRelative" in {
        Accessors.Uri("http://example.com/abc")
          .toRelative must be(Accessors.Uri("/abc"))
      }
      "pathSegments" in {
        Accessors.Uri("/abc/def/ghi/jkl")
          .pathSegments().asScala.toSeq must contain inOrderOnly ("abc", "def", "ghi", "jkl")
      }
      "access parameterMap" in {
        Accessors.Uri("/abc?name=blub&age=28")
          .parameterMap().asScala must contain allOf ("name" -> "blub", "age" -> "28")
      }
      "access parameters" in {
        val Seq(param1, param2, param3) =
          Accessors.Uri("/abc?name=blub&age=28&name=blub2")
            .parameters.asScala.toSeq

        param1.getKey must be("name")
        param1.getValue must be("blub")

        param2.getKey must be("age")
        param2.getValue must be("28")

        param3.getKey must be("name")
        param3.getValue must be("blub2")
      }
      "containsParameter" in {
        val uri = Accessors.Uri("/abc?name=blub")
        uri.containsParameter("name") must be(true)
        uri.containsParameter("age") must be(false)
      }
      "access single parameter" in {
        val uri = Accessors.Uri("/abc?name=blub")
        uri.parameter("name") must be(akka.japi.Option.some("blub"))
        uri.parameter("age") must be(akka.japi.Option.none)

        Accessors.Uri("/abc?name=blub&name=blib").parameter("name") must be(akka.japi.Option.some("blub"))
      }
    }
  }
}
