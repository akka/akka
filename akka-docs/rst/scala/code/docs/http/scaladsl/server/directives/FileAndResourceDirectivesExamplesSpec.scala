/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.directives.DirectoryListing
import akka.http.scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer
import docs.http.scaladsl.server.RoutingSpec
import docs.http.scaladsl.server.RoutingSpec

class FileAndResourceDirectivesExamplesSpec extends RoutingSpec {
  "getFromFile-examples" in compileOnlySpec {
    import akka.http.scaladsl.server.directives._
    import ContentTypeResolver.Default

    val route =
      path("logs" / Segment) { name =>
        getFromFile(".log") // uses implicit ContentTypeResolver
      }

    // tests:
    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
  }
  "getFromResource-examples" in compileOnlySpec {
    import akka.http.scaladsl.server.directives._
    import ContentTypeResolver.Default

    val route =
      path("logs" / Segment) { name =>
        getFromResource(".log") // uses implicit ContentTypeResolver
      }

    // tests:
    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
  }
  "listDirectoryContents-examples" in compileOnlySpec {
    val route =
      path("tmp") {
        listDirectoryContents("/tmp")
      } ~
        path("custom") {
          val renderer = new DirectoryRenderer {
            override def marshaller(renderVanityFooter: Boolean): ToEntityMarshaller[DirectoryListing] = ???
          }
          listDirectoryContents("/tmp")(renderer)
        }

    // tests:
    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
  }
  "getFromBrowseableDirectory-examples" in compileOnlySpec {
    val route =
      path("tmp") {
        getFromBrowseableDirectory("/tmp")
      }

    // tests:
    Get("/tmp") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
  "getFromBrowseableDirectories-examples" in compileOnlySpec {
    val route =
      path("tmp") {
        getFromBrowseableDirectories("/main", "/backups")
      }

    // tests:
    Get("/tmp") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
  "getFromDirectory-examples" in compileOnlySpec {
    val route =
      path("tmp") {
        getFromDirectory("/tmp")
      }

    // tests:
    Get("/tmp/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
  }
  "getFromResourceDirectory-examples" in compileOnlySpec {
    val route =
      path("examples") {
        getFromResourceDirectory("/examples")
      }

    // tests:
    Get("/examples/example-1") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
  }

}
