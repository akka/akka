/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.io.File

import scala.annotation.varargs
import scala.collection.JavaConverters._

import akka.http.javadsl.model.ContentType
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Marshaller
import akka.http.javadsl.server.Route
import akka.http.scaladsl
import akka.http.scaladsl.server.{ Directives ⇒ D }

/**
 * Implement this interface to provide a custom mapping from a file name to a [[akka.http.javadsl.model.ContentType]].
 */
trait ContentTypeResolver {
  def resolve(fileName: String): ContentType
}

abstract class DirectoryListing {
  def getPath: String
  def isRoot: Boolean
  def getFiles: java.util.List[File]
}

trait DirectoryRenderer {
  def marshaller(renderVanityFooter: Boolean): Marshaller[DirectoryListing, RequestEntity]
}

private[directives] object FileAndResourceDirectives {
  /** INTERNAL API */
  implicit def scalaResolver(resolver: ContentTypeResolver) =
    new scaladsl.server.directives.ContentTypeResolver {
      override def apply(fileName: String) = resolver.resolve(fileName)
    }

  /** INTERNAL API */
  implicit def scalaRenderer(renderer: DirectoryRenderer) =
    new scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer {
      override def marshaller(renderVanityFooter: Boolean) = renderer.marshaller(renderVanityFooter).asScala
    }
}

/**
 * Directives that load files and resources.
 *
 * For the directives in this class, the "default classloader" is defined as the classloader that has loaded
 * the akka.actor.ActorSystem class.
 */
abstract class FileAndResourceDirectives extends ExecutionDirectives {
  import FileAndResourceDirectives._

  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader,
   * using the default content type resolver.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String): Route = ScalaRoute {
    D.getFromResource(path)
  }

  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader,
   * using the given content type resolver.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromResource(path)(resolver)
  }

  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader,
   * with the given content type.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, contentType: ContentType): Route = ScalaRoute {
    D.getFromResource(path, contentType)
  }

  /**
   * Completes GET requests with the content of the given resource loaded from the given ClassLoader,
   * with the given content type.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, contentType: ContentType, classLoader: ClassLoader): Route = ScalaRoute {
    D.getFromResource(path, contentType, classLoader)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the default ClassLoader, resolving content type using the default content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String): Route = ScalaRoute {
    D.getFromResourceDirectory(directoryName)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the given ClassLoader, resolving content type using the default content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String, classLoader: ClassLoader): Route = ScalaRoute {
    D.getFromResourceDirectory(directoryName, classLoader)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the default ClassLoader, resolving content type using the given content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromResourceDirectory(directoryName)(resolver)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the given ClassLoader, resolving content type using the given content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String, resolver: ContentTypeResolver, classLoader: ClassLoader): Route = ScalaRoute {
    D.getFromResourceDirectory(directoryName, classLoader)(resolver)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the default resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File): Route = ScalaRoute {
    D.getFromFile(file)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the given resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromFile(file)(resolver)
  }

  /**
   * Completes GET requests with the content of the given file, using the content type.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File, contentType: ContentType): Route = ScalaRoute {
    D.getFromFile(file, contentType)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the default resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: String): Route = ScalaRoute {
    D.getFromFile(file)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the given resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: String, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromFile(file)(resolver)
  }

  /**
   * Completes GET requests with the content of a file underneath the given directory, using the default content-type resolver.
   * If the file cannot be read the Route rejects the request.
   */
  def getFromDirectory(directoryPath: String): Route = ScalaRoute {
    D.getFromDirectory(directoryPath)
  }

  /**
   * Completes GET requests with the content of a file underneath the given directory, using the given content-type resolver.
   * If the file cannot be read the Route rejects the request.
   */
  def getFromDirectory(directoryPath: String, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromDirectory(directoryPath)(resolver)
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String, renderer: DirectoryRenderer, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromBrowseableDirectory(directory)(renderer, resolver)
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String, renderer: DirectoryRenderer): Route = ScalaRoute {
    D.getFromBrowseableDirectory(directory)(renderer = renderer,
      resolver = implicitly[scaladsl.server.directives.ContentTypeResolver])
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromBrowseableDirectory(directory)(resolver = resolver,
      renderer = implicitly[scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer])
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String): Route = ScalaRoute {
    D.getFromBrowseableDirectory(directory)
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  def getFromBrowseableDirectories(directories: java.lang.Iterable[String], renderer: DirectoryRenderer, resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromBrowseableDirectories(directories.asScala.toSeq: _*)(renderer, resolver)
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  def getFromBrowseableDirectories(directories: java.lang.Iterable[String], renderer: DirectoryRenderer): Route = ScalaRoute {
    D.getFromBrowseableDirectories(directories.asScala.toSeq: _*)(renderer = renderer,
      resolver = implicitly[scaladsl.server.directives.ContentTypeResolver])
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  def getFromBrowseableDirectories(directories: java.lang.Iterable[String], resolver: ContentTypeResolver): Route = ScalaRoute {
    D.getFromBrowseableDirectories(directories.asScala.toSeq: _*)(resolver = resolver,
      renderer = implicitly[scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer])
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  @varargs def getFromBrowseableDirectories(directories: String*): Route = ScalaRoute {
    D.getFromBrowseableDirectories(directories: _*)
  }
}
