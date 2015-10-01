/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server.directives

import java.io.File

import akka.event.Logging
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.DirectoryListing
import akka.http.scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer
import akka.stream.ActorMaterializer
import akka.stream.io.SynchronousFileSource
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import docs.http.scaladsl.server.RoutingSpec

import scala.concurrent.Future
import scala.util.control.NonFatal

class FileAndResourceDirectivesExamplesSpec extends RoutingSpec {
  "0getFromFile" in compileOnlySpec {
    import akka.http.scaladsl.server.directives._
    import ContentTypeResolver.Default

    val route =
      path("logs" / Segment) { name =>
        getFromFile(".log") // uses implicit ContentTypeResolver
      }

    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "The length of the request URI is 25"
    }
  }
  "0getFromResource" in compileOnlySpec {
    import akka.http.scaladsl.server.directives._
    import ContentTypeResolver.Default

    val route =
      path("logs" / Segment) { name =>
        getFromResource(".log") // uses implicit ContentTypeResolver
      }

    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "The length of the request URI is 25"
    }
  }
  "0listDirectoryContents" in compileOnlySpec {
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

    Get("/logs/example") ~> route ~> check {
      responseAs[String] shouldEqual "The length of the request URI is 25"
    }
  }

  private def compileOnlySpec(block: => Unit) = pending
}
