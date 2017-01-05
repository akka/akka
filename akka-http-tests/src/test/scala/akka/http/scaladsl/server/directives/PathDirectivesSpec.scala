/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import scala.collection.immutable.ListMap
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import org.scalatest.Inside

class PathDirectivesSpec extends RoutingSpec with Inside {
  val echoUnmatchedPath = extractUnmatchedPath { echoComplete }
  def echoCaptureAndUnmatchedPath[T]: T ⇒ Route =
    capture ⇒ ctx ⇒ ctx.complete(capture.toString + ":" + ctx.unmatchedPath)

  """path("foo")""" should {
    val test = testFor(path("foo") { echoUnmatchedPath })
    "reject [/bar]" inThe test()
    "reject [/foobar]" inThe test()
    "reject [/foo/bar]" inThe test()
    "accept [/foo] and clear the unmatchedPath" inThe test("")
    "reject [/foo/]" inThe test()
  }

  """pathPrefix("")""" should {
    val test = testFor(pathPrefix("") { echoUnmatchedPath })

    // Should match everything because pathPrefix is used and "" is a neutral element.
    "accept [/] and clear the unmatchedPath=" inThe test("")
    "accept [/foo] and clear the unmatchedPath" inThe test("foo")
    "accept [/foo/] and clear the unmatchedPath" inThe test("foo/")
    "accept [/bar/] and clear the unmatchedPath" inThe test("bar/")
  }

  """path("" | "foo")""" should {
    val test = testFor(path("" | "foo") { echoUnmatchedPath })

    // Should not match anything apart of "/", because path requires whole path being matched.
    "accept [/] and clear the unmatchedPath=" inThe test("")
    "reject [/foo]" inThe test()
    "reject [/foo/]" inThe test()
    "reject [/bar/]" inThe test()
  }

  """path("") ~ path("foo")""" should {
    val test = testFor(path("")(echoUnmatchedPath) ~ path("foo")(echoUnmatchedPath))

    // Should match both because ~ operator is used for two exclusive routes.
    "accept [/] and clear the unmatchedPath=" inThe test("")
    "accept [/foo] and clear the unmatchedPath=" inThe test("")
  }

  """path("foo" /)""" should {
    val test = testFor(path("foo" /) { echoUnmatchedPath })
    "reject [/foo]" inThe test()
    "accept [/foo/] and clear the unmatchedPath" inThe test("")
  }

  """path("")""" should {
    val test = testFor(path("") { echoUnmatchedPath })
    "reject [/foo]" inThe test()
    "accept [/] and clear the unmatchedPath" in test("")
  }

  """path(Map("a" → 1, "aa" → 2))""" should {
    val test = testFor(path(Map("a" → 1, "aa" → 2)) { echoCaptureAndUnmatchedPath })
    "accept [/a]" inThe test("1:")
    "accept [/aa]" inThe test("2:")
    "reject [/aaa]" inThe test()
  }

  """path(Map("sv_SE" → 3, "sv" → 1, "sv_FI" → 2))""" should {
    val test = testFor(path(Map("sv_SE" → 3, "sv" → 1, "sv_FI" → 2)) { echoCaptureAndUnmatchedPath })
    "accept [/sv]" inThe test("1:")
    "accept [/sv_FI]" inThe test("2:")
    "accept [/sv_SE]" inThe test("3:")
    "reject [/sv_DK]" inThe test()
  }

  """path(Map("a" -> 1, "ab" -> 2, "ba" -> 3, "b" -> 4, "c" -> 5, "d" -> 6, "da" -> 7))""" should {
    val test = testFor(path(Map("a" → 1, "ab" → 2, "ba" → 3, "b" → 4, "c" → 5, "d" → 6, "da" → 7)) { echoCaptureAndUnmatchedPath })
    "accept [/a]" inThe test("1:")
    "accept [/ab]" inThe test("2:") // FAIL
    "accept [/ba]" inThe test("3:")
    "accept [/b]" inThe test("4:")
    "accept [/c]" inThe test("5:")
    "accept [/d]" inThe test("6:")
    "accept [/da]" inThe test("7:")
    "reject [/e]" inThe test()
    "reject [/ac]" inThe test()
  }

  """path(ListMap("a" -> 1, "ab" -> 2, "ba" -> 3, "b" -> 4, "c" -> 5, "d" -> 6, "da" -> 7))""" should {
    val test = testFor(path(ListMap("a" → 1, "ab" → 2, "ba" → 3, "b" → 4, "c" → 5, "d" → 6, "da" → 7)) { echoCaptureAndUnmatchedPath })
    "accept [/a]" inThe test("1:")
    "accept [/ab]" inThe test("2:")
    "accept [/ba]" inThe test("3:")
    "accept [/b]" inThe test("4:")
    "accept [/c]" inThe test("5:")
    "accept [/d]" inThe test("6:")
    "accept [/da]" inThe test("7:")
    "reject [/e]" inThe test()
    "reject [/ac]" inThe test()
  }

  """path(ListMap("a" -> 1, "aa" -> 2, "bb" -> 3, "b" -> 4, "c" -> 5, "d" -> 6, "dd" -> 7))""" should {
    val test = testFor(path(ListMap("a" → 1, "aa" → 2, "bb" → 3, "b" → 4, "c" → 5, "d" → 6, "dd" → 7)) { echoCaptureAndUnmatchedPath })
    "accept [/a]" inThe test("1:")
    "accept [/aa]" inThe test("2:")
    "accept [/bb]" inThe test("3:")
    "accept [/b]" inThe test("4:")
    "accept [/c]" inThe test("5:")
    "accept [/d]" inThe test("6:")
    "accept [/dd]" inThe test("7:")
    "reject [/e]" inThe test()
    "reject [/ac]" inThe test()
  }

  """path(Map("a" -> 1, "aa" -> 2, "bb" -> 3, "b" -> 4, "c" -> 5, "d" -> 6, "dd" -> 7))""" should {
    val test = testFor(path(Map("a" → 1, "aa" → 2, "bb" → 3, "b" → 4, "c" → 5, "d" → 6, "dd" → 7)) { echoCaptureAndUnmatchedPath })
    "accept [/a]" inThe test("1:")
    "accept [/aa]" inThe test("2:")
    "accept [/bb]" inThe test("3:")
    "accept [/b]" inThe test("4:")
    "accept [/c]" inThe test("5:")
    "accept [/d]" inThe test("6:")
    "accept [/dd]" inThe test("7:")
    "reject [/e]" inThe test()
    "reject [/ac]" inThe test()
  }

  """pathPrefix("foo")""" should {
    val test = testFor(pathPrefix("foo") { echoUnmatchedPath })
    "reject [/bar]" inThe test()
    "accept [/foobar]" inThe test("bar")
    "accept [/foo/bar]" inThe test("/bar")
    "accept [/foo] and clear the unmatchedPath" inThe test("")
    "accept [/foo/] and clear the unmatchedPath" inThe test("/")
  }

  """pathPrefix("foo" / "bar")""" should {
    val test = testFor(pathPrefix("foo" / "bar") { echoUnmatchedPath })
    "reject [/bar]" inThe test()
    "accept [/foo/bar]" inThe test("")
    "accept [/foo/bar/baz]" inThe test("/baz")
  }

  """pathPrefix("ab[cd]+".r)""" should {
    val test = testFor(pathPrefix("ab[cd]+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" inThe test()
    "reject [/ab/cd]" inThe test()
    "accept [/abcdef]" inThe test("abcd:ef")
    "accept [/abcdd/ef]" inThe test("abcdd:/ef")
  }

  """pathPrefix("ab(cd)".r)""" should {
    val test = testFor(pathPrefix("ab(cd)+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" inThe test()
    "reject [/ab/cd]" inThe test()
    "accept [/abcdef]" inThe test("cd:ef")
    "accept [/abcde/fg]" inThe test("cd:e/fg")
  }

  "pathPrefix(regex)" should {
    "fail when the regex contains more than one group" in {
      an[IllegalArgumentException] must be thrownBy path("a(b+)(c+)".r) { echoCaptureAndUnmatchedPath }
    }
  }

  "pathPrefix(IntNumber)" should {
    val test = testFor(pathPrefix(IntNumber) { echoCaptureAndUnmatchedPath })
    "accept [/23]" inThe test("23:")
    "accept [/12345yes]" inThe test("12345:yes")
    "reject [/]" inThe test()
    "reject [/abc]" inThe test()
    "reject [/2147483648]" inThe test() // > Int.MaxValue
  }

  "pathPrefix(CustomShortNumber)" should {
    object CustomShortNumber extends NumberMatcher[Short](Short.MaxValue, 10) {
      def fromChar(c: Char) = fromDecimalChar(c)
    }

    val test = testFor(pathPrefix(CustomShortNumber) { echoCaptureAndUnmatchedPath })
    "accept [/23]" inThe test("23:")
    "accept [/12345yes]" inThe test("12345:yes")
    "reject [/]" inThe test()
    "reject [/abc]" inThe test()
    "reject [/33000]" inThe test() // > Short.MaxValue
  }

  "pathPrefix(JavaUUID)" should {
    val test = testFor(pathPrefix(JavaUUID) { echoCaptureAndUnmatchedPath })
    "accept [/bdea8652-f26c-40ca-8157-0b96a2a8389d]" inThe test("bdea8652-f26c-40ca-8157-0b96a2a8389d:")
    "accept [/bdea8652-f26c-40ca-8157-0b96a2a8389dyes]" inThe test("bdea8652-f26c-40ca-8157-0b96a2a8389d:yes")
    "reject [/]" inThe test()
    "reject [/abc]" inThe test()
  }

  "pathPrefix(Map(\"red\" -> 1, \"green\" -> 2, \"blue\" -> 3))" should {
    val test = testFor(pathPrefix(Map("red" → 1, "green" → 2, "blue" → 3)) { echoCaptureAndUnmatchedPath })
    "accept [/green]" inThe test("2:")
    "accept [/redsea]" inThe test("1:sea")
    "reject [/black]" inThe test()
  }

  "pathPrefix(Map.empty)" should {
    val test = testFor(pathPrefix(Map[String, Int]()) { echoCaptureAndUnmatchedPath })
    "reject [/black]" inThe test()
  }

  "pathPrefix(Segment)" should {
    val test = testFor(pathPrefix(Segment) { echoCaptureAndUnmatchedPath })
    "accept [/abc]" inThe test("abc:")
    "accept [/abc/]" inThe test("abc:/")
    "accept [/abc/def]" inThe test("abc:/def")
    "reject [/]" inThe test()
  }

  "pathPrefix(Segments)" should {
    val test = testFor(pathPrefix(Segments) { echoCaptureAndUnmatchedPath })
    "accept [/]" inThe test("List():")
    "accept [/a/b/c]" inThe test("List(a, b, c):")
    "accept [/a/b/c/]" inThe test("List(a, b, c):/")
  }

  """pathPrefix(separateOnSlashes("a/b"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("a/b")) { echoUnmatchedPath })
    "accept [/a/b]" inThe test("")
    "accept [/a/b/]" inThe test("/")
    "reject [/a/c]" inThe test()
  }
  """pathPrefix(separateOnSlashes("abc"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("abc")) { echoUnmatchedPath })
    "accept [/abc]" inThe test("")
    "accept [/abcdef]" inThe test("def")
    "reject [/ab]" inThe test()
  }

  """pathPrefixTest("a" / Segment ~ Slash)""" should {
    val test = testFor(pathPrefixTest("a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" inThe test("bc:/a/bc/")
    "reject [/a/bc]" inThe test()
    "reject [/a/]" inThe test()
  }

  """pathSuffix("edit" / Segment)""" should {
    val test = testFor(pathSuffix("edit" / Segment) { echoCaptureAndUnmatchedPath })
    "accept [/orders/123/edit]" inThe test("123:/orders/")
    "reject [/orders/123/ed]" inThe test()
    "reject [/edit]" inThe test()
  }

  """pathSuffix("foo" / "bar" ~ "baz")""" should {
    val test = testFor(pathSuffix("foo" / "bar" ~ "baz") { echoUnmatchedPath })
    "accept [/orders/barbaz/foo]" inThe test("/orders/")
    "reject [/orders/bazbar/foo]" inThe test()
  }

  "pathSuffixTest(Slash)" should {
    val test = testFor(pathSuffixTest(Slash) { echoUnmatchedPath })
    "accept [/]" inThe test("/")
    "accept [/foo/]" inThe test("/foo/")
    "reject [/foo]" inThe test()
  }

  """pathPrefix("foo" | "bar")""" should {
    val test = testFor(pathPrefix("foo" | "bar") { echoUnmatchedPath })
    "accept [/foo]" inThe test("")
    "accept [/foops]" inThe test("ps")
    "accept [/bar]" inThe test("")
    "reject [/baz]" inThe test()
  }

  """pathSuffix(!"foo")""" should {
    val test = testFor(pathSuffix(!"foo") { echoUnmatchedPath })
    "accept [/bar]" inThe test("/bar")
    "reject [/foo]" inThe test()
  }

  "pathPrefix(IntNumber?)" should {
    val test = testFor(pathPrefix(IntNumber?) { echoCaptureAndUnmatchedPath })
    "accept [/12]" inThe test("Some(12):")
    "accept [/12a]" inThe test("Some(12):a")
    "accept [/foo]" inThe test("None:foo")
  }

  """pathPrefix("foo"?)""" should {
    val test = testFor(pathPrefix("foo"?) { echoUnmatchedPath })
    "accept [/foo]" inThe test("")
    "accept [/fool]" inThe test("l")
    "accept [/bar]" inThe test("bar")
  }

  """pathPrefix("foo") & pathEnd""" should {
    val test = testFor((pathPrefix("foo") & pathEnd) { echoUnmatchedPath })
    "reject [/foobar]" inThe test()
    "reject [/foo/bar]" inThe test()
    "accept [/foo] and clear the unmatchedPath" inThe test("")
    "reject [/foo/]" inThe test()
  }

  """pathPrefix("foo") & pathEndOrSingleSlash""" should {
    val test = testFor((pathPrefix("foo") & pathEndOrSingleSlash) { echoUnmatchedPath })
    "reject [/foobar]" inThe test()
    "reject [/foo/bar]" inThe test()
    "accept [/foo] and clear the unmatchedPath" inThe test("")
    "accept [/foo/] and clear the unmatchedPath" inThe test("")
  }

  """pathPrefix(IntNumber.repeat(separator = "."))""" should {
    {
      val test = testFor(pathPrefix(IntNumber.repeat(min = 2, max = 5, separator = ".")) { echoCaptureAndUnmatchedPath })
      "reject [/foo]" inThe test()
      "reject [/1foo]" inThe test()
      "reject [/1.foo]" inThe test()
      "accept [/1.2foo]" inThe test("List(1, 2):foo")
      "accept [/1.2.foo]" inThe test("List(1, 2):.foo")
      "accept [/1.2.3foo]" inThe test("List(1, 2, 3):foo")
      "accept [/1.2.3.foo]" inThe test("List(1, 2, 3):.foo")
      "accept [/1.2.3.4foo]" inThe test("List(1, 2, 3, 4):foo")
      "accept [/1.2.3.4.foo]" inThe test("List(1, 2, 3, 4):.foo")
      "accept [/1.2.3.4.5foo]" inThe test("List(1, 2, 3, 4, 5):foo")
      "accept [/1.2.3.4.5.foo]" inThe test("List(1, 2, 3, 4, 5):.foo")
      "accept [/1.2.3.4.5.6foo]" inThe test("List(1, 2, 3, 4, 5):.6foo")
      "accept [/1.2.3.]" inThe test("List(1, 2, 3):.")
      "accept [/1.2.3/]" inThe test("List(1, 2, 3):/")
      "accept [/1.2.3./]" inThe test("List(1, 2, 3):./")
    }
    {
      val test = testFor(pathPrefix(IntNumber.repeat(2, ".")) { echoCaptureAndUnmatchedPath })
      "reject [/bar]" inThe test()
      "reject [/1bar]" inThe test()
      "reject [/1.bar]" inThe test()
      "accept [/1.2bar]" inThe test("List(1, 2):bar")
      "accept [/1.2.bar]" inThe test("List(1, 2):.bar")
      "accept [/1.2.3bar]" inThe test("List(1, 2):.3bar")
    }
  }

  """rawPathPrefix(Slash ~ "a" / Segment ~ Slash)""" should {
    val test = testFor(rawPathPrefix(Slash ~ "a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" inThe test("bc:")
    "reject [/a/bc]" inThe test()
    "reject [/ab/]" inThe test()
  }

  """rawPathPrefixTest(Slash ~ "a" / Segment ~ Slash)""" should {
    val test = testFor(rawPathPrefixTest(Slash ~ "a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" inThe test("bc:/a/bc/")
    "reject [/a/bc]" inThe test()
    "reject [/ab/]" inThe test()
  }

  "PathMatchers" should {
    {
      val test = testFor(path(Remaining.tmap { case Tuple1(s) ⇒ Tuple1(s.split('-').toList) }) { echoComplete })
      "support the hmap modifier in accept [/yes-no]" inThe test("List(yes, no)")
    }
    {
      val test = testFor(path(Remaining.map(_.split('-').toList)) { echoComplete })
      "support the map modifier in accept [/yes-no]" inThe test("List(yes, no)")
    }
    {
      val test = testFor(path(Remaining.tflatMap { case Tuple1(s) ⇒ Some(s).filter("yes" ==).map(x ⇒ Tuple1(x)) }) { echoComplete })
      "support the hflatMap modifier in accept [/yes]" inThe test("yes")
      "support the hflatMap modifier in reject [/blub]" inThe test()
    }
    {
      val test = testFor(path(Remaining.flatMap(s ⇒ Some(s).filter("yes" ==))) { echoComplete })
      "support the flatMap modifier in accept [/yes]" inThe test("yes")
      "support the flatMap modifier reject [/blub]" inThe test()
    }
  }

  implicit class WithIn(str: String) {
    def inThe(f: String ⇒ Unit) = convertToWordSpecStringWrapper(str) in f(str)
    def inThe(body: ⇒ Unit) = convertToWordSpecStringWrapper(str) in body
  }

  case class testFor(route: Route) {
    def apply(expectedResponse: String = null): String ⇒ Unit = exampleString ⇒
      """(accept|reject)\s+\[([^\]]+)\]""".r.findFirstMatchIn(exampleString) match {
        case Some(uri) ⇒
          uri.group(1) match {
            case "accept" if expectedResponse eq null ⇒
              failTest("Example '" + exampleString + "' was missing an expectedResponse")
            case "reject" if expectedResponse ne null ⇒
              failTest("Example '" + exampleString + "' had an expectedResponse")
            case _ ⇒
          }

          Get(uri.group(2)) ~> route ~> check {
            if (expectedResponse eq null) handled shouldEqual false
            else responseAs[String] shouldEqual expectedResponse
          }
        case None ⇒ failTest("Example '" + exampleString + "' doesn't contain a test uri")
      }
  }

  import akka.http.scaladsl.model.StatusCodes._

  "the Remaining path matcher" should {
    "extract complete path if nothing previously consumed" in {
      val route = path(Remaining) { echoComplete }
      Get("/pets/afdaoisd/asda/sfasfasf/asf") ~> route ~> check { responseAs[String] shouldEqual "pets/afdaoisd/asda/sfasfasf/asf" }
    }
    "extract remaining path when parts of path already matched" in {
      val route = path("pets" / Remaining) { echoComplete }
      Get("/pets/afdaoisd/asda/sfasfasf/asf") ~> route ~> check { responseAs[String] shouldEqual "afdaoisd/asda/sfasfasf/asf" }
    }
  }

  "the `redirectToTrailingSlashIfMissing` directive" should {
    val route = redirectToTrailingSlashIfMissing(Found) { completeOk }

    "pass if the request path already has a trailing slash" in {
      Get("/foo/bar/") ~> route ~> check { response shouldEqual Ok }
    }

    "redirect if the request path doesn't have a trailing slash" in {
      Get("/foo/bar") ~> route ~> checkRedirectTo("/foo/bar/")
    }

    "preserves the query and the frag when redirect" in {
      Get("/foo/bar?query#frag") ~> route ~> checkRedirectTo("/foo/bar/?query#frag")
    }

    "redirect with the given redirection status code" in {
      Get("/foo/bar") ~>
        redirectToTrailingSlashIfMissing(MovedPermanently) { completeOk } ~>
        check { status shouldEqual MovedPermanently }

      Get("/foo/bar/") ~>
        redirectToTrailingSlashIfMissing(MovedPermanently) { completeOk } ~>
        check { status shouldEqual StatusCodes.OK }
    }
  }

  "the `redirectToNoTrailingSlashIfPresent` directive" should {
    val route = redirectToNoTrailingSlashIfPresent(Found) { completeOk }

    "pass if the request path already doesn't have a trailing slash" in {
      Get("/foo/bar") ~> route ~> check { response shouldEqual Ok }
    }

    "redirect if the request path has a trailing slash" in {
      Get("/foo/bar/") ~> route ~> checkRedirectTo("/foo/bar")
    }

    "preserves the query and the frag when redirect" in {
      Get("/foo/bar/?query#frag") ~> route ~> checkRedirectTo("/foo/bar?query#frag")
    }

    "redirect with the given redirection status code" in {
      Get("/foo/bar/") ~>
        redirectToNoTrailingSlashIfPresent(MovedPermanently) { completeOk } ~>
        check { status shouldEqual MovedPermanently }
    }
  }

  import akka.http.scaladsl.model.headers.Location
  import akka.http.scaladsl.model.Uri

  private def checkRedirectTo(expectedUri: Uri) =
    check {
      status shouldBe a[Redirection]
      inside(header[Location]) {
        case Some(Location(uri)) ⇒
          (if (expectedUri.isAbsolute) uri else uri.toRelative) shouldEqual expectedUri
      }
    }
}
