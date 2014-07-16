/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.routing._
import org.scalatest.matchers.MatchResult

class PathDirectivesSpec extends RoutingSpec {
  val echoUnmatchedPath = unmatchedPath { echoComplete }
  def echoCaptureAndUnmatchedPath[T]: T ⇒ Route =
    capture ⇒ ctx ⇒ ctx.complete(capture.toString + ":" + ctx.unmatchedPath)

  implicit class WithIn(str: String) {
    def in(f: String ⇒ Unit) = f(str)
    def in(body: ⇒ Unit) = body
  }

  case class testFor(route: Route) {
    def apply(expectedResponse: String = null): String ⇒ Unit = exampleString ⇒
      "\\[([^\\]]+)\\]".r.findFirstMatchIn(exampleString) match {
        case Some(uri) ⇒ Get(uri.group(1)) ~> route ~> check {
          if (expectedResponse eq null) handled mustEqual (false)
          else responseAs[String] mustEqual expectedResponse
        }
        case None ⇒ failTest("Example '" + exampleString + "' doesn't contain a test uri")
      }
  }

  """path("foo")""" should {
    val test = testFor(path("foo") { echoUnmatchedPath })
    "reject [/bar]" in test()
    "reject [/foobar]" in test()
    "reject [/foo/bar]" in test()
    "accept [/foo] and clear the unmatchedPath" in test("")
    "reject [/foo/]" in test()
  }
  """path("foo" /)""" should {
    val test = testFor(path("foo" /) { echoUnmatchedPath })
    "reject [/foo]" in test()
    "accept [/foo/] and clear the unmatchedPath" in test("")
  }
  """path("")""" should {
    val test = testFor(path("") { echoUnmatchedPath })
    "reject [/foo]" in test()
    "accept [/] and clear the unmatchedPath" in test("")
  }

  """pathPrefix("foo")""" should {
    val test = testFor(pathPrefix("foo") { echoUnmatchedPath })
    "reject [/bar]" in test()
    "accept [/foobar]" in test("bar")
    "accept [/foo/bar]" in test("/bar")
    "accept [/foo] and clear the unmatchedPath" in test("")
    "accept [/foo/] and clear the unmatchedPath" in test("/")
  }

  """pathPrefix("foo" / "bar")""" should {
    val test = testFor(pathPrefix("foo" / "bar") { echoUnmatchedPath })
    "reject [/bar]" in test()
    "accept [/foo/bar]" in test("")
    "accept [/foo/bar/baz]" in test("/baz")
  }

  """pathPrefix("ab[cd]+".r)""" should {
    val test = testFor(pathPrefix("ab[cd]+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" in test()
    "reject [/ab/cd]" in test()
    "reject [/abcdef]" in test("abcd:ef")
    "reject [/abcdd/ef]" in test("abcdd:/ef")
  }

  """pathPrefix("ab(cd)".r)""" should {
    val test = testFor(pathPrefix("ab(cd)+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" in test()
    "reject [/ab/cd]" in test()
    "reject [/abcdef]" in test("cd:ef")
    "reject [/abcde/fg]" in test("cd:e/fg")
  }

  "pathPrefix(regex)" should {
    "fail when the regex contains more than one group" in {
      an[IllegalArgumentException] must be thrownBy path("a(b+)(c+)".r) { echoCaptureAndUnmatchedPath }
    }
  }

  "pathPrefix(IntNumber)" should {
    val test = testFor(pathPrefix(IntNumber) { echoCaptureAndUnmatchedPath })
    "accept [/23]" in test("23:")
    "accept [/12345yes]" in test("12345:yes")
    "reject [/]" in test()
    "reject [/abc]" in test()
    "reject [/2147483648]" in test() // > Int.MaxValue
  }

  "pathPrefix(JavaUUID)" should {
    val test = testFor(pathPrefix(JavaUUID) { echoCaptureAndUnmatchedPath })
    "accept [/bdea8652-f26c-40ca-8157-0b96a2a8389d]" in test("bdea8652-f26c-40ca-8157-0b96a2a8389d:")
    "accept [/bdea8652-f26c-40ca-8157-0b96a2a8389dyes]" in test("bdea8652-f26c-40ca-8157-0b96a2a8389d:yes")
    "reject [/]" in test()
    "reject [/abc]" in test()
  }

  "pathPrefix(Map(\"red\" -> 1, \"green\" -> 2, \"blue\" -> 3))" should {
    val test = testFor(pathPrefix(Map("red" -> 1, "green" -> 2, "blue" -> 3)) { echoCaptureAndUnmatchedPath })
    "accept [/green]" in test("2:")
    "accept [/redsea]" in test("1:sea")
    "reject [/black]" in test()
  }

  "pathPrefix(Segment)" should {
    val test = testFor(pathPrefix(Segment) { echoCaptureAndUnmatchedPath })
    "accept [/abc]" in test("abc:")
    "accept [/abc/]" in test("abc:/")
    "accept [/abc/def]" in test("abc:/def")
    "reject [/]" in test()
  }

  "pathPrefix(Segments)" should {
    val test = testFor(pathPrefix(Segments) { echoCaptureAndUnmatchedPath })
    "accept [/]" in test("List():")
    "accept [/a/b/c]" in test("List(a, b, c):")
    "accept [/a/b/c/]" in test("List(a, b, c):/")
  }

  """pathPrefix(separateOnSlashes("a/b"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("a/b")) { echoUnmatchedPath })
    "accept [/a/b]" in test("")
    "accept [/a/b/]" in test("/")
    "accept [/a/c]" in test()
  }
  """pathPrefix(separateOnSlashes("abc"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("abc")) { echoUnmatchedPath })
    "accept [/abc]" in test("")
    "accept [/abcdef]" in test("def")
    "accept [/ab]" in test()
  }

  """pathPrefixTest("a" / Segment ~ Slash)""" should {
    val test = testFor(pathPrefixTest("a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" in test("bc:/a/bc/")
    "accept [/a/bc]" in test()
    "accept [/a/]" in test()
  }

  """pathSuffix("edit" / Segment)""" should {
    val test = testFor(pathSuffix("edit" / Segment) { echoCaptureAndUnmatchedPath })
    "accept [/orders/123/edit]" in test("123:/orders/")
    "accept [/orders/123/ed]" in test()
    "accept [/edit]" in test()
  }

  """pathSuffix("foo" / "bar" ~ "baz")""" should {
    val test = testFor(pathSuffix("foo" / "bar" ~ "baz") { echoUnmatchedPath })
    "accept [/orders/barbaz/foo]" in test("/orders/")
    "accept [/orders/bazbar/foo]" in test()
  }

  "pathSuffixTest(Slash)" should {
    val test = testFor(pathSuffixTest(Slash) { echoUnmatchedPath })
    "accept [/]" in test("/")
    "accept [/foo/]" in test("/foo/")
    "accept [/foo]" in test()
  }

  """pathPrefix("foo" | "bar")""" in {
    val test = testFor(pathPrefix("foo" | "bar") { echoUnmatchedPath })
    "accept [/foo]" in test("")
    "accept [/foops]" in test("ps")
    "accept [/bar]" in test("")
    "reject [/baz]" in test()
  }

  """pathSuffix(!"foo")""" in {
    val test = testFor(pathSuffix(!"foo") { echoUnmatchedPath })
    "accept [/bar]" in test("/bar")
    "reject [/foo]" in test()
  }

  "pathPrefix(IntNumber?)" in {
    val test = testFor(pathPrefix(IntNumber?) { echoCaptureAndUnmatchedPath })
    "accept [/12]" in test("Some(12):")
    "accept [/12a]" in test("Some(12):a")
    "accept [/foo]" in test("None:foo")
  }

  """pathPrefix("foo"?)""" in {
    val test = testFor(pathPrefix("foo"?) { echoUnmatchedPath })
    "accept [/foo]" in test("")
    "accept [/fool]" in test("l")
    "accept [/bar]" in test("bar")
  }

  """pathPrefix("foo") & pathEnd""" in {
    val test = testFor((pathPrefix("foo") & pathEnd) { echoUnmatchedPath })
    "reject [/foobar]" in test()
    "reject [/foo/bar]" in test()
    "accept [/foo] and clear the unmatchedPath" in test("")
    "reject [/foo/]" in test()
  }

  """pathPrefix("foo") & pathEndOrSingleSlash""" in {
    val test = testFor((pathPrefix("foo") & pathEndOrSingleSlash) { echoUnmatchedPath })
    "reject [/foobar]" in test()
    "reject [/foo/bar]" in test()
    "accept [/foo] and clear the unmatchedPath" in test("")
    "accept [/foo/] and clear the unmatchedPath" in test("")
  }

  """pathPrefix(IntNumber.repeat(separator = "|"))""" in {
    val test = testFor(pathPrefix(IntNumber.repeat(separator = "|")) { echoCaptureAndUnmatchedPath })
    "accept [/1|2|3rest]" in test("List(1, 2, 3):rest")
    "accept [/rest]" in test("List():rest")
  }

  "PathMatchers" should {
    import akka.shapeless._
    "support the hmap modifier" in {
      val test = testFor(path(Rest.hmap { case s :: HNil ⇒ s.split('-').toList :: HNil }) { echoComplete })
      "accept [/yes-no]" in test("List(yes, no)")
    }
    "support the map modifier" in {
      val test = testFor(path(Rest.map(_.split('-').toList)) { echoComplete })
      "accept [/yes-no]" in test("List(yes, no)")
    }
    "support the hflatMap modifier" in {
      val test = testFor(path(Rest.hflatMap { case s :: HNil ⇒ Some(s).filter("yes" ==).map(_ :: HNil) }) { echoComplete })
      "accept [/yes]" in test("yes")
      "reject [/blub]" in test()
    }
    "support the flatMap modifier" in {
      val test = testFor(path(Rest.flatMap(s ⇒ Some(s).filter("yes" ==))) { echoComplete })
      "accept [/yes]" in test("yes")
      "reject [/blub]" in test()
    }
  }
}