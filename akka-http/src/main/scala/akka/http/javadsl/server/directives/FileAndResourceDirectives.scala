/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.io.File

import scala.annotation.varargs
import scala.collection.JavaConverters._
import akka.http.javadsl.model.ContentType
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.server.{ Route, RoutingJavaMapping }
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
  def directoryMarshaller(renderVanityFooter: Boolean): akka.http.javadsl.server.Marshaller[DirectoryListing, RequestEntity]
}

/**
 * Directives that load files and resources.
 *
 * For the directives in this class, the "default classloader" is defined as the classloader that has loaded
 * the akka.actor.ActorSystem class.
 */
abstract class FileAndResourceDirectives extends ExecutionDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._
  import RoutingJavaMapping._

  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader,
   * using the default content type resolver.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String): Route = RouteAdapter {
    D.getFromResource(path)
  }

  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader,
   * using the given content type resolver.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromResource(path)(resolver.asScala)
  }

  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader,
   * with the given content type.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, contentType: ContentType): Route = RouteAdapter {
    D.getFromResource(path, contentType.asScala)
  }

  /**
   * Completes GET requests with the content of the given resource loaded from the given ClassLoader,
   * with the given content type.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, contentType: ContentType, classLoader: ClassLoader): Route = RouteAdapter {
    D.getFromResource(path, contentType.asScala, classLoader)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the default ClassLoader, resolving content type using the default content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String): Route = RouteAdapter {
    D.getFromResourceDirectory(directoryName)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the given ClassLoader, resolving content type using the default content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String, classLoader: ClassLoader): Route = RouteAdapter {
    D.getFromResourceDirectory(directoryName, classLoader)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the default ClassLoader, resolving content type using the given content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromResourceDirectory(directoryName)(resolver.asScala)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory", using the given ClassLoader, resolving content type using the given content type
   * resolver.
   *
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   */
  def getFromResourceDirectory(directoryName: String, resolver: ContentTypeResolver, classLoader: ClassLoader): Route = RouteAdapter {
    D.getFromResourceDirectory(directoryName, classLoader)(resolver.asScala)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the default resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File): Route = RouteAdapter {
    D.getFromFile(file)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the given resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromFile(file)(resolver.asScala)
  }

  /**
   * Completes GET requests with the content of the given file, using the content type.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File, contentType: ContentType): Route = RouteAdapter {
    D.getFromFile(file, contentType.asScala)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the default resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: String): Route = RouteAdapter {
    D.getFromFile(file)
  }

  /**
   * Completes GET requests with the content of the given file, resolving the content type using the given resolver.
   * If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: String, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromFile(file)(resolver.asScala)
  }

  /**
   * Completes GET requests with the content of a file underneath the given directory, using the default content-type resolver.
   * If the file cannot be read the Route rejects the request.
   */
  def getFromDirectory(directoryPath: String): Route = RouteAdapter {
    D.getFromDirectory(directoryPath)
  }

  /**
   * Completes GET requests with the content of a file underneath the given directory, using the given content-type resolver.
   * If the file cannot be read the Route rejects the request.
   */
  def getFromDirectory(directoryPath: String, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromDirectory(directoryPath)(resolver.asScala)
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String, renderer: DirectoryRenderer, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromBrowseableDirectory(directory)(renderer.asScala, resolver.asScala)
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String, renderer: DirectoryRenderer): Route = RouteAdapter {
    D.getFromBrowseableDirectory(directory)(renderer.asScala, defaultContentTypeResolver.asScala)
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromBrowseableDirectory(directory)(defaultDirectoryRenderer.asScala, resolver.asScala)
  }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String): Route = RouteAdapter {
    D.getFromBrowseableDirectory(directory)
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  def getFromBrowseableDirectories(directories: java.lang.Iterable[String], renderer: DirectoryRenderer, resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromBrowseableDirectories(directories.asScala.toSeq: _*)(renderer.asScala, resolver.asScala)
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  def getFromBrowseableDirectories(directories: java.lang.Iterable[String], renderer: DirectoryRenderer): Route = RouteAdapter {
    D.getFromBrowseableDirectories(directories.asScala.toSeq: _*)(renderer.asScala, defaultContentTypeResolver.asScala)
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  def getFromBrowseableDirectories(directories: java.lang.Iterable[String], resolver: ContentTypeResolver): Route = RouteAdapter {
    D.getFromBrowseableDirectories(directories.asScala.toSeq: _*)(defaultDirectoryRenderer.asScala, resolver.asScala)
  }

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   */
  @varargs def getFromBrowseableDirectories(directories: String*): Route = RouteAdapter {
    D.getFromBrowseableDirectories(directories: _*)
  }

  /**
   * Completes GET requests with a unified listing of the contents of all given directories.
   * The actual rendering of the directory contents is performed by the in-scope `Marshaller[DirectoryListing]`.
   */
  @varargs def listDirectoryContents(directories: String*): Route = RouteAdapter {
    D.listDirectoryContents(directories: _*)(defaultDirectoryRenderer.asScala)
  }
  /**
   * Completes GET requests with a unified listing of the contents of all given directories.
   * The actual rendering of the directory contents is performed by the in-scope `Marshaller[DirectoryListing]`.
   */
  @varargs def listDirectoryContents(directoryRenderer: DirectoryRenderer, directories: String*): Route = RouteAdapter {
    D.listDirectoryContents(directories: _*)(directoryRenderer.asScala)
  }

  /** Default [[DirectoryRenderer]] to be used with directory listing directives. */
  def defaultDirectoryRenderer: DirectoryRenderer =
    akka.http.scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer.defaultDirectoryRenderer

  /** Default [[ContentTypeResolver]]. */
  def defaultContentTypeResolver: ContentTypeResolver =
    akka.http.scaladsl.server.directives.ContentTypeResolver.Default
}
