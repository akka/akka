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

class PathDirectivesSpec extends RoutingSpec {
  val echoUnmatchedPath = unmatchedPath { echoComplete }
  def echoCaptureAndUnmatchedPath[T]: T ⇒ Route =
    capture ⇒ ctx ⇒ ctx.complete(capture.toString + ":" + ctx.unmatchedPath)

  implicit class WithIn(str: String) {
    def in(f: String ⇒ Unit) = convertToWordSpecStringWrapper(str) in (f(str))
    def inPendingUntilFixed(f: String ⇒ Unit) = convertToWordSpecStringWrapper(str) in pendingUntilFixed(f(str))
    def in(body: ⇒ Unit) = convertToWordSpecStringWrapper(str) in body
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
    "reject [/bar]" inPendingUntilFixed (test())
    "reject [/foobar]" inPendingUntilFixed test()
    "reject [/foo/bar]" inPendingUntilFixed test()
    "accept [/foo] and clear the unmatchedPath" inPendingUntilFixed test("")
    "reject [/foo/]" inPendingUntilFixed test()
  }
  """path("foo" /)""" should {
    val test = testFor(path("foo" /) { echoUnmatchedPath })
    "reject [/foo]" inPendingUntilFixed test()
    "accept [/foo/] and clear the unmatchedPath" inPendingUntilFixed test("")
  }
  """path("")""" should {
    val test = testFor(path("") { echoUnmatchedPath })
    "reject [/foo]" inPendingUntilFixed test()
    "accept [/] and clear the unmatchedPath" inPendingUntilFixed test("")
  }

  """pathPrefix("foo")""" should {
    val test = testFor(pathPrefix("foo") { echoUnmatchedPath })
    "reject [/bar]" inPendingUntilFixed test()
    "accept [/foobar]" inPendingUntilFixed test("bar")
    "accept [/foo/bar]" inPendingUntilFixed test("/bar")
    "accept [/foo] and clear the unmatchedPath" inPendingUntilFixed test("")
    "accept [/foo/] and clear the unmatchedPath" inPendingUntilFixed test("/")
  }

  """pathPrefix("foo" / "bar")""" should {
    val test = testFor(pathPrefix("foo" / "bar") { echoUnmatchedPath })
    "reject [/bar]" inPendingUntilFixed test()
    "accept [/foo/bar]" inPendingUntilFixed test("")
    "accept [/foo/bar/baz]" inPendingUntilFixed test("/baz")
  }

  """pathPrefix("ab[cd]+".r)""" should {
    val test = testFor(pathPrefix("ab[cd]+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" inPendingUntilFixed test()
    "reject [/ab/cd]" inPendingUntilFixed test()
    "reject [/abcdef]" inPendingUntilFixed test("abcd:ef")
    "reject [/abcdd/ef]" inPendingUntilFixed test("abcdd:/ef")
  }

  """pathPrefix("ab(cd)".r)""" should {
    val test = testFor(pathPrefix("ab(cd)+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" inPendingUntilFixed test()
    "reject [/ab/cd]" inPendingUntilFixed test()
    "reject [/abcdef]" inPendingUntilFixed test("cd:ef")
    "reject [/abcde/fg]" inPendingUntilFixed test("cd:e/fg")
  }

  "pathPrefix(regex)" should {
    "fail when the regex contains more than one group" in pendingUntilFixed {
      an[IllegalArgumentException] must be thrownBy path("a(b+)(c+)".r) { echoCaptureAndUnmatchedPath }
    }
  }

  "pathPrefix(IntNumber)" should {
    val test = testFor(pathPrefix(IntNumber) { echoCaptureAndUnmatchedPath })
    "accept [/23]" inPendingUntilFixed test("23:")
    "accept [/12345yes]" inPendingUntilFixed test("12345:yes")
    "reject [/]" inPendingUntilFixed test()
    "reject [/abc]" inPendingUntilFixed test()
    "reject [/2147483648]" inPendingUntilFixed test() // > Int.MaxValue
  }

  "pathPrefix(JavaUUID)" should {
    val test = testFor(pathPrefix(JavaUUID) { echoCaptureAndUnmatchedPath })
    "accept [/bdea8652-f26c-40ca-8157-0b96a2a8389d]" inPendingUntilFixed test("bdea8652-f26c-40ca-8157-0b96a2a8389d:")
    "accept [/bdea8652-f26c-40ca-8157-0b96a2a8389dyes]" inPendingUntilFixed test("bdea8652-f26c-40ca-8157-0b96a2a8389d:yes")
    "reject [/]" inPendingUntilFixed test()
    "reject [/abc]" inPendingUntilFixed test()
  }

  "pathPrefix(Map(\"red\" -> 1, \"green\" -> 2, \"blue\" -> 3))" should {
    val test = testFor(pathPrefix(Map("red" -> 1, "green" -> 2, "blue" -> 3)) { echoCaptureAndUnmatchedPath })
    "accept [/green]" inPendingUntilFixed test("2:")
    "accept [/redsea]" inPendingUntilFixed test("1:sea")
    "reject [/black]" inPendingUntilFixed test()
  }

  "pathPrefix(Segment)" should {
    val test = testFor(pathPrefix(Segment) { echoCaptureAndUnmatchedPath })
    "accept [/abc]" inPendingUntilFixed test("abc:")
    "accept [/abc/]" inPendingUntilFixed test("abc:/")
    "accept [/abc/def]" inPendingUntilFixed test("abc:/def")
    "reject [/]" inPendingUntilFixed test()
  }

  "pathPrefix(Segments)" should {
    val test = testFor(pathPrefix(Segments) { echoCaptureAndUnmatchedPath })
    "accept [/]" inPendingUntilFixed test("List():")
    "accept [/a/b/c]" inPendingUntilFixed test("List(a, b, c):")
    "accept [/a/b/c/]" inPendingUntilFixed test("List(a, b, c):/")
  }

  """pathPrefix(separateOnSlashes("a/b"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("a/b")) { echoUnmatchedPath })
    "accept [/a/b]" inPendingUntilFixed test("")
    "accept [/a/b/]" inPendingUntilFixed test("/")
    "accept [/a/c]" inPendingUntilFixed test()
  }
  """pathPrefix(separateOnSlashes("abc"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("abc")) { echoUnmatchedPath })
    "accept [/abc]" inPendingUntilFixed test("")
    "accept [/abcdef]" inPendingUntilFixed test("def")
    "accept [/ab]" inPendingUntilFixed test()
  }

  """pathPrefixTest("a" / Segment ~ Slash)""" should {
    val test = testFor(pathPrefixTest("a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" inPendingUntilFixed test("bc:/a/bc/")
    "accept [/a/bc]" inPendingUntilFixed test()
    "accept [/a/]" inPendingUntilFixed test()
  }

  """pathSuffix("edit" / Segment)""" should {
    val test = testFor(pathSuffix("edit" / Segment) { echoCaptureAndUnmatchedPath })
    "accept [/orders/123/edit]" inPendingUntilFixed test("123:/orders/")
    "accept [/orders/123/ed]" inPendingUntilFixed test()
    "accept [/edit]" inPendingUntilFixed test()
  }

  """pathSuffix("foo" / "bar" ~ "baz")""" should {
    val test = testFor(pathSuffix("foo" / "bar" ~ "baz") { echoUnmatchedPath })
    "accept [/orders/barbaz/foo]" inPendingUntilFixed test("/orders/")
    "accept [/orders/bazbar/foo]" inPendingUntilFixed test()
  }

  "pathSuffixTest(Slash)" should {
    val test = testFor(pathSuffixTest(Slash) { echoUnmatchedPath })
    "accept [/]" inPendingUntilFixed test("/")
    "accept [/foo/]" inPendingUntilFixed test("/foo/")
    "accept [/foo]" inPendingUntilFixed test()
  }

  """pathPrefix("foo" | "bar")""" in pendingUntilFixed {
    val test = testFor(pathPrefix("foo" | "bar") { echoUnmatchedPath })
    "accept [/foo]" inPendingUntilFixed test("")
    "accept [/foops]" inPendingUntilFixed test("ps")
    "accept [/bar]" inPendingUntilFixed test("")
    "reject [/baz]" inPendingUntilFixed test()
  }

  """pathSuffix(!"foo")""" in pendingUntilFixed {
    val test = testFor(pathSuffix(!"foo") { echoUnmatchedPath })
    "accept [/bar]" inPendingUntilFixed test("/bar")
    "reject [/foo]" inPendingUntilFixed test()
  }

  "pathPrefix(IntNumber?)" in pendingUntilFixed {
    val test = testFor(pathPrefix(IntNumber?) { echoCaptureAndUnmatchedPath })
    "accept [/12]" inPendingUntilFixed test("Some(12):")
    "accept [/12a]" inPendingUntilFixed test("Some(12):a")
    "accept [/foo]" inPendingUntilFixed test("None:foo")
  }

  """pathPrefix("foo"?)""" in pendingUntilFixed {
    val test = testFor(pathPrefix("foo"?) { echoUnmatchedPath })
    "accept [/foo]" inPendingUntilFixed test("")
    "accept [/fool]" inPendingUntilFixed test("l")
    "accept [/bar]" inPendingUntilFixed test("bar")
  }

  """pathPrefix("foo") & pathEnd""" in pendingUntilFixed {
    val test = testFor((pathPrefix("foo") & pathEnd) { echoUnmatchedPath })
    "reject [/foobar]" inPendingUntilFixed test()
    "reject [/foo/bar]" inPendingUntilFixed test()
    "accept [/foo] and clear the unmatchedPath" inPendingUntilFixed test("")
    "reject [/foo/]" inPendingUntilFixed test()
  }

  """pathPrefix("foo") & pathEndOrSingleSlash""" in pendingUntilFixed {
    val test = testFor((pathPrefix("foo") & pathEndOrSingleSlash) { echoUnmatchedPath })
    "reject [/foobar]" inPendingUntilFixed test()
    "reject [/foo/bar]" inPendingUntilFixed test()
    "accept [/foo] and clear the unmatchedPath" inPendingUntilFixed test("")
    "accept [/foo/] and clear the unmatchedPath" inPendingUntilFixed test("")
  }

  """pathPrefix(IntNumber.repeat(separator = "|"))""" in pendingUntilFixed {
    val test = testFor(pathPrefix(IntNumber.repeat(separator = "|")) { echoCaptureAndUnmatchedPath })
    "accept [/1|2|3rest]" inPendingUntilFixed test("List(1, 2, 3):rest")
    "accept [/rest]" inPendingUntilFixed test("List():rest")
  }

  "PathMatchers" should {
    import akka.shapeless._
    "support the hmap modifier" in pendingUntilFixed {
      val test = testFor(path(Rest.hmap { case s :: HNil ⇒ s.split('-').toList :: HNil }) { echoComplete })
      "accept [/yes-no]" inPendingUntilFixed test("List(yes, no)")
    }
    "support the map modifier" in pendingUntilFixed {
      val test = testFor(path(Rest.map(_.split('-').toList)) { echoComplete })
      "accept [/yes-no]" inPendingUntilFixed test("List(yes, no)")
    }
    "support the hflatMap modifier" in pendingUntilFixed {
      val test = testFor(path(Rest.hflatMap { case s :: HNil ⇒ Some(s).filter("yes" ==).map(_ :: HNil) }) { echoComplete })
      "accept [/yes]" inPendingUntilFixed test("yes")
      "reject [/blub]" inPendingUntilFixed test()
    }
    "support the flatMap modifier" in pendingUntilFixed {
      val test = testFor(path(Rest.flatMap(s ⇒ Some(s).filter("yes" ==))) { echoComplete })
      "accept [/yes]" inPendingUntilFixed test("yes")
      "reject [/blub]" inPendingUntilFixed test()
    }
  }
}