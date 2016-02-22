/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.server._
import docs.http.scaladsl.server.RoutingSpec

class PathDirectivesExamplesSpec extends RoutingSpec {

  //# path-matcher
  val matcher: PathMatcher1[Option[Int]] =
    "foo" / "bar" / "X" ~ IntNumber.? / ("edit" | "create")
  //#

  //# path-dsl
  // matches /foo/
  path("foo"./)

  // matches e.g. /foo/123 and extracts "123" as a String
  path("foo" / """\d+""".r)

  // matches e.g. /foo/bar123 and extracts "123" as a String
  path("foo" / """bar(\d+)""".r)

  // similar to `path(Segments)`
  path(Segment.repeat(10, separator = Slash))

  // matches e.g. /i42 or /hCAFE and extracts an Int
  path("i" ~ IntNumber | "h" ~ HexIntNumber)

  // identical to path("foo" ~ (PathEnd | Slash))
  path("foo" ~ Slash.?)

  // matches /red or /green or /blue and extracts 1, 2 or 3 respectively
  path(Map("red" -> 1, "green" -> 2, "blue" -> 3))

  // matches anything starting with "/foo" except for /foobar
  pathPrefix("foo" ~ !"bar")
  //#

  //# pathPrefixTest-, rawPathPrefix-, rawPathPrefixTest-, pathSuffix-, pathSuffixTest-
  val completeWithUnmatchedPath =
    extractUnmatchedPath { p =>
      complete(p.toString)
    }

  //#

  "path-example" in {
    val route =
      path("foo") {
        complete("/foo")
      } ~
        path("foo" / "bar") {
          complete("/foo/bar")
        } ~
        pathPrefix("ball") {
          pathEnd {
            complete("/ball")
          } ~
            path(IntNumber) { int =>
              complete(if (int % 2 == 0) "even ball" else "odd ball")
            }
        }

    // tests:
    Get("/") ~> route ~> check {
      handled shouldEqual false
    }

    Get("/foo") ~> route ~> check {
      responseAs[String] shouldEqual "/foo"
    }

    Get("/foo/bar") ~> route ~> check {
      responseAs[String] shouldEqual "/foo/bar"
    }

    Get("/ball/1337") ~> route ~> check {
      responseAs[String] shouldEqual "odd ball"
    }
  }

  "pathEnd-" in {
    val route =
      pathPrefix("foo") {
        pathEnd {
          complete("/foo")
        } ~
          path("bar") {
            complete("/foo/bar")
          }
      }

    // tests:
    Get("/foo") ~> route ~> check {
      responseAs[String] shouldEqual "/foo"
    }

    Get("/foo/") ~> route ~> check {
      handled shouldEqual false
    }

    Get("/foo/bar") ~> route ~> check {
      responseAs[String] shouldEqual "/foo/bar"
    }
  }

  "pathEndOrSingleSlash-" in {
    val route =
      pathPrefix("foo") {
        pathEndOrSingleSlash {
          complete("/foo")
        } ~
          path("bar") {
            complete("/foo/bar")
          }
      }

    // tests:
    Get("/foo") ~> route ~> check {
      responseAs[String] shouldEqual "/foo"
    }

    Get("/foo/") ~> route ~> check {
      responseAs[String] shouldEqual "/foo"
    }

    Get("/foo/bar") ~> route ~> check {
      responseAs[String] shouldEqual "/foo/bar"
    }
  }

  "pathPrefix-" in {
    val route =
      pathPrefix("ball") {
        pathEnd {
          complete("/ball")
        } ~
          path(IntNumber) { int =>
            complete(if (int % 2 == 0) "even ball" else "odd ball")
          }
      }

    // tests:
    Get("/") ~> route ~> check {
      handled shouldEqual false
    }

    Get("/ball") ~> route ~> check {
      responseAs[String] shouldEqual "/ball"
    }

    Get("/ball/1337") ~> route ~> check {
      responseAs[String] shouldEqual "odd ball"
    }
  }

  "pathPrefixTest-" in {
    val route =
      pathPrefixTest("foo" | "bar") {
        pathPrefix("foo") { completeWithUnmatchedPath } ~
          pathPrefix("bar") { completeWithUnmatchedPath }
      }

    // tests:
    Get("/foo/doo") ~> route ~> check {
      responseAs[String] shouldEqual "/doo"
    }

    Get("/bar/yes") ~> route ~> check {
      responseAs[String] shouldEqual "/yes"
    }
  }

  "pathSingleSlash-" in {
    val route =
      pathSingleSlash {
        complete("root")
      } ~
        pathPrefix("ball") {
          pathSingleSlash {
            complete("/ball/")
          } ~
            path(IntNumber) { int =>
              complete(if (int % 2 == 0) "even ball" else "odd ball")
            }
        }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "root"
    }

    Get("/ball") ~> route ~> check {
      handled shouldEqual false
    }

    Get("/ball/") ~> route ~> check {
      responseAs[String] shouldEqual "/ball/"
    }

    Get("/ball/1337") ~> route ~> check {
      responseAs[String] shouldEqual "odd ball"
    }
  }

  "pathSuffix-" in {
    val route =
      pathPrefix("start") {
        pathSuffix("end") {
          completeWithUnmatchedPath
        } ~
          pathSuffix("foo" / "bar" ~ "baz") {
            completeWithUnmatchedPath
          }
      }

    // tests:
    Get("/start/middle/end") ~> route ~> check {
      responseAs[String] shouldEqual "/middle/"
    }

    Get("/start/something/barbaz/foo") ~> route ~> check {
      responseAs[String] shouldEqual "/something/"
    }
  }

  "pathSuffixTest-" in {
    val route =
      pathSuffixTest(Slash) {
        complete("slashed")
      } ~
        complete("unslashed")

    // tests:
    Get("/foo/") ~> route ~> check {
      responseAs[String] shouldEqual "slashed"
    }
    Get("/foo") ~> route ~> check {
      responseAs[String] shouldEqual "unslashed"
    }
  }

  "rawPathPrefix-" in {
    val route =
      pathPrefix("foo") {
        rawPathPrefix("bar") { completeWithUnmatchedPath } ~
          rawPathPrefix("doo") { completeWithUnmatchedPath }
      }

    // tests:
    Get("/foobar/baz") ~> route ~> check {
      responseAs[String] shouldEqual "/baz"
    }

    Get("/foodoo/baz") ~> route ~> check {
      responseAs[String] shouldEqual "/baz"
    }
  }

  "rawPathPrefixTest-" in {
    val route =
      pathPrefix("foo") {
        rawPathPrefixTest("bar") {
          completeWithUnmatchedPath
        }
      }

    // tests:
    Get("/foobar") ~> route ~> check {
      responseAs[String] shouldEqual "bar"
    }

    Get("/foobaz") ~> route ~> check {
      handled shouldEqual false
    }
  }

  "redirectToTrailingSlashIfMissing-0" in {
    import akka.http.scaladsl.model.StatusCodes

    val route =
      redirectToTrailingSlashIfMissing(StatusCodes.MovedPermanently) {
        path("foo"./) {
          // We require the explicit trailing slash in the path
          complete("OK")
        } ~
          path("bad-1") {
            // MISTAKE!
            // Missing `/` in path, causes this path to never match,
            // because it is inside a `redirectToTrailingSlashIfMissing`
            ???
          } ~
          path("bad-2/") {
            // MISTAKE!
            // / should be explicit as path element separator and not *in* the path element
            // So it should be: "bad-1" /
            ???
          }
      }

    // tests:
    // Redirected:
    Get("/foo") ~> route ~> check {
      status shouldEqual StatusCodes.MovedPermanently

      // results in nice human readable message,
      // in case the redirect can't be followed automatically:
      responseAs[String] shouldEqual {
        "This and all future requests should be directed to " +
          "<a href=\"http://example.com/foo/\">this URI</a>."
      }
    }

    // Properly handled:
    Get("/foo/") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "OK"
    }

    // MISTAKE! will never match - reason explained in routes
    Get("/bad-1/") ~> route ~> check {
      handled shouldEqual false
    }

    // MISTAKE! will never match - reason explained in routes
    Get("/bad-2/") ~> route ~> check {
      handled shouldEqual false
    }
  }

  "redirectToNoTrailingSlashIfPresent-0" in {
    import akka.http.scaladsl.model.StatusCodes

    val route =
      redirectToNoTrailingSlashIfPresent(StatusCodes.MovedPermanently) {
        path("foo") {
          // We require the explicit trailing slash in the path
          complete("OK")
        } ~
          path("bad"./) {
            // MISTAKE!
            // Since inside a `redirectToNoTrailingSlashIfPresent` directive
            // the matched path here will never contain a trailing slash,
            // thus this path will never match.
            //
            // It should be `path("bad")` instead.
            ???
          }
      }

    // tests:
    // Redirected:
    Get("/foo/") ~> route ~> check {
      status shouldEqual StatusCodes.MovedPermanently

      // results in nice human readable message,
      // in case the redirect can't be followed automatically:
      responseAs[String] shouldEqual {
        "This and all future requests should be directed to " +
          "<a href=\"http://example.com/foo\">this URI</a>."
      }
    }

    // Properly handled:
    Get("/foo") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "OK"
    }

    // MISTAKE! will never match - reason explained in routes
    Get("/bad") ~> route ~> check {
      handled shouldEqual false
    }
  }
}
