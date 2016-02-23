/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import org.scalatest.Inside

class PathDirectivesSpec extends RoutingSpec with Inside {
  val echoUnmatchedPath = extractUnmatchedPath { echoComplete }
  def echoCaptureAndUnmatchedPath[T]: T ⇒ Route =
    capture ⇒ ctx ⇒ ctx.complete(capture.toString + ":" + ctx.unmatchedPath)

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
    "accept [/abcdef]" in test("abcd:ef")
    "accept [/abcdd/ef]" in test("abcdd:/ef")
  }

  """pathPrefix("ab(cd)".r)""" should {
    val test = testFor(pathPrefix("ab(cd)+".r) { echoCaptureAndUnmatchedPath })
    "reject [/bar]" in test()
    "reject [/ab/cd]" in test()
    "accept [/abcdef]" in test("cd:ef")
    "accept [/abcde/fg]" in test("cd:e/fg")
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

  "pathPrefix(CustomShortNumber)" should {
    object CustomShortNumber extends NumberMatcher[Short](Short.MaxValue, 10) {
      def fromChar(c: Char) = fromDecimalChar(c)
    }

    val test = testFor(pathPrefix(CustomShortNumber) { echoCaptureAndUnmatchedPath })
    "accept [/23]" in test("23:")
    "accept [/12345yes]" in test("12345:yes")
    "reject [/]" in test()
    "reject [/abc]" in test()
    "reject [/33000]" in test() // > Short.MaxValue
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

  "pathPrefix(Map.empty)" should {
    val test = testFor(pathPrefix(Map[String, Int]()) { echoCaptureAndUnmatchedPath })
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
    "reject [/a/c]" in test()
  }
  """pathPrefix(separateOnSlashes("abc"))""" should {
    val test = testFor(pathPrefix(separateOnSlashes("abc")) { echoUnmatchedPath })
    "accept [/abc]" in test("")
    "accept [/abcdef]" in test("def")
    "reject [/ab]" in test()
  }

  """pathPrefixTest("a" / Segment ~ Slash)""" should {
    val test = testFor(pathPrefixTest("a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" in test("bc:/a/bc/")
    "reject [/a/bc]" in test()
    "reject [/a/]" in test()
  }

  """pathSuffix("edit" / Segment)""" should {
    val test = testFor(pathSuffix("edit" / Segment) { echoCaptureAndUnmatchedPath })
    "accept [/orders/123/edit]" in test("123:/orders/")
    "reject [/orders/123/ed]" in test()
    "reject [/edit]" in test()
  }

  """pathSuffix("foo" / "bar" ~ "baz")""" should {
    val test = testFor(pathSuffix("foo" / "bar" ~ "baz") { echoUnmatchedPath })
    "accept [/orders/barbaz/foo]" in test("/orders/")
    "reject [/orders/bazbar/foo]" in test()
  }

  "pathSuffixTest(Slash)" should {
    val test = testFor(pathSuffixTest(Slash) { echoUnmatchedPath })
    "accept [/]" in test("/")
    "accept [/foo/]" in test("/foo/")
    "reject [/foo]" in test()
  }

  """pathPrefix("foo" | "bar")""" should {
    val test = testFor(pathPrefix("foo" | "bar") { echoUnmatchedPath })
    "accept [/foo]" in test("")
    "accept [/foops]" in test("ps")
    "accept [/bar]" in test("")
    "reject [/baz]" in test()
  }

  """pathSuffix(!"foo")""" should {
    val test = testFor(pathSuffix(!"foo") { echoUnmatchedPath })
    "accept [/bar]" in test("/bar")
    "reject [/foo]" in test()
  }

  "pathPrefix(IntNumber?)" should {
    val test = testFor(pathPrefix(IntNumber?) { echoCaptureAndUnmatchedPath })
    "accept [/12]" in test("Some(12):")
    "accept [/12a]" in test("Some(12):a")
    "accept [/foo]" in test("None:foo")
  }

  """pathPrefix("foo"?)""" should {
    val test = testFor(pathPrefix("foo"?) { echoUnmatchedPath })
    "accept [/foo]" in test("")
    "accept [/fool]" in test("l")
    "accept [/bar]" in test("bar")
  }

  """pathPrefix("foo") & pathEnd""" should {
    val test = testFor((pathPrefix("foo") & pathEnd) { echoUnmatchedPath })
    "reject [/foobar]" in test()
    "reject [/foo/bar]" in test()
    "accept [/foo] and clear the unmatchedPath" in test("")
    "reject [/foo/]" in test()
  }

  """pathPrefix("foo") & pathEndOrSingleSlash""" should {
    val test = testFor((pathPrefix("foo") & pathEndOrSingleSlash) { echoUnmatchedPath })
    "reject [/foobar]" in test()
    "reject [/foo/bar]" in test()
    "accept [/foo] and clear the unmatchedPath" in test("")
    "accept [/foo/] and clear the unmatchedPath" in test("")
  }

  """pathPrefix(IntNumber.repeat(separator = "."))""" should {
    {
      val test = testFor(pathPrefix(IntNumber.repeat(min = 2, max = 5, separator = ".")) { echoCaptureAndUnmatchedPath })
      "reject [/foo]" in test()
      "reject [/1foo]" in test()
      "reject [/1.foo]" in test()
      "accept [/1.2foo]" in test("List(1, 2):foo")
      "accept [/1.2.foo]" in test("List(1, 2):.foo")
      "accept [/1.2.3foo]" in test("List(1, 2, 3):foo")
      "accept [/1.2.3.foo]" in test("List(1, 2, 3):.foo")
      "accept [/1.2.3.4foo]" in test("List(1, 2, 3, 4):foo")
      "accept [/1.2.3.4.foo]" in test("List(1, 2, 3, 4):.foo")
      "accept [/1.2.3.4.5foo]" in test("List(1, 2, 3, 4, 5):foo")
      "accept [/1.2.3.4.5.foo]" in test("List(1, 2, 3, 4, 5):.foo")
      "accept [/1.2.3.4.5.6foo]" in test("List(1, 2, 3, 4, 5):.6foo")
      "accept [/1.2.3.]" in test("List(1, 2, 3):.")
      "accept [/1.2.3/]" in test("List(1, 2, 3):/")
      "accept [/1.2.3./]" in test("List(1, 2, 3):./")
    }
    {
      val test = testFor(pathPrefix(IntNumber.repeat(2, ".")) { echoCaptureAndUnmatchedPath })
      "reject [/bar]" in test()
      "reject [/1bar]" in test()
      "reject [/1.bar]" in test()
      "accept [/1.2bar]" in test("List(1, 2):bar")
      "accept [/1.2.bar]" in test("List(1, 2):.bar")
      "accept [/1.2.3bar]" in test("List(1, 2):.3bar")
    }
  }

  """rawPathPrefix(Slash ~ "a" / Segment ~ Slash)""" should {
    val test = testFor(rawPathPrefix(Slash ~ "a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" in test("bc:")
    "reject [/a/bc]" in test()
    "reject [/ab/]" in test()
  }

  """rawPathPrefixTest(Slash ~ "a" / Segment ~ Slash)""" should {
    val test = testFor(rawPathPrefixTest(Slash ~ "a" / Segment ~ Slash) { echoCaptureAndUnmatchedPath })
    "accept [/a/bc/]" in test("bc:/a/bc/")
    "reject [/a/bc]" in test()
    "reject [/ab/]" in test()
  }

  "PathMatchers" should {
    {
      val test = testFor(path(Rest.tmap { case Tuple1(s) ⇒ Tuple1(s.split('-').toList) }) { echoComplete })
      "support the hmap modifier in accept [/yes-no]" in test("List(yes, no)")
    }
    {
      val test = testFor(path(Rest.map(_.split('-').toList)) { echoComplete })
      "support the map modifier in accept [/yes-no]" in test("List(yes, no)")
    }
    {
      val test = testFor(path(Rest.tflatMap { case Tuple1(s) ⇒ Some(s).filter("yes" ==).map(x ⇒ Tuple1(x)) }) { echoComplete })
      "support the hflatMap modifier in accept [/yes]" in test("yes")
      "support the hflatMap modifier in reject [/blub]" in test()
    }
    {
      val test = testFor(path(Rest.flatMap(s ⇒ Some(s).filter("yes" ==))) { echoComplete })
      "support the flatMap modifier in accept [/yes]" in test("yes")
      "support the flatMap modifier reject [/blub]" in test()
    }
  }

  implicit class WithIn(str: String) {
    def in(f: String ⇒ Unit) = convertToWordSpecStringWrapper(str) in f(str)
    def in(body: ⇒ Unit) = convertToWordSpecStringWrapper(str) in body
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
