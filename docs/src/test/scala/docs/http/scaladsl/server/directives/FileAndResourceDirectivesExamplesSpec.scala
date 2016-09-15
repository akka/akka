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
    //#getFromFile-examples
    import akka.http.scaladsl.server.directives._
    import ContentTypeResolver.Default

    val route =
      path("logs" / Segment) { name =>
        getFromFile(s"$name.log") // uses implicit ContentTypeResolver
      }

    // tests:
    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
    //#getFromFile-examples
  }
  "getFromResource-examples" in compileOnlySpec {
    //#getFromResource-examples
    import akka.http.scaladsl.server.directives._
    import ContentTypeResolver.Default

    val route =
      path("logs" / Segment) { name =>
        getFromResource(s"$name.log") // uses implicit ContentTypeResolver
      }

    // tests:
    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
    //#getFromResource-examples
  }
  "listDirectoryContents-examples" in compileOnlySpec {
    //#listDirectoryContents-examples
    val route =
      path("tmp") {
        listDirectoryContents("/tmp")
      } ~
        path("custom") {
          // implement your custom renderer here
          val renderer = new DirectoryRenderer {
            override def marshaller(renderVanityFooter: Boolean): ToEntityMarshaller[DirectoryListing] = ???
          }
          listDirectoryContents("/tmp")(renderer)
        }

    // tests:
    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
    //#listDirectoryContents-examples
  }
  "getFromBrowseableDirectory-examples" in compileOnlySpec {
    //#getFromBrowseableDirectory-examples
    val route =
      path("tmp") {
        getFromBrowseableDirectory("/tmp")
      }

    // tests:
    Get("/tmp") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
    //#getFromBrowseableDirectory-examples
  }
  "getFromBrowseableDirectories-examples" in compileOnlySpec {
    //#getFromBrowseableDirectories-examples
    val route =
      path("tmp") {
        getFromBrowseableDirectories("/main", "/backups")
      }

    // tests:
    Get("/tmp") ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
    //#getFromBrowseableDirectories-examples
  }
  "getFromDirectory-examples" in compileOnlySpec {
    //#getFromDirectory-examples
    val route =
      pathPrefix("tmp") {
        getFromDirectory("/tmp")
      }

    // tests:
    Get("/tmp/example") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
    //#getFromDirectory-examples
  }
  "getFromResourceDirectory-examples" in compileOnlySpec {
    //#getFromResourceDirectory-examples
    val route =
      pathPrefix("examples") {
        getFromResourceDirectory("/examples")
      }

    // tests:
    Get("/examples/example-1") ~> route ~> check {
      responseAs[String] shouldEqual "example file contents"
    }
    //#getFromResourceDirectory-examples
  }

}
