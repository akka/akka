/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.io.File
import akka.http.javadsl.model.{ ContentType }
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server
import akka.http.impl.server.RouteStructure._

/**
 * Implement this interface to provide a custom mapping from a file name to a [[akka.http.javadsl.model.ContentType]].
 */
trait ContentTypeResolver {
  def resolve(fileName: String): ContentType
}

/**
 * A resolver that assumes the given constant [[akka.http.javadsl.model.ContentType]] for all files.
 */
case class StaticContentTypeResolver(contentType: ContentType) extends ContentTypeResolver {
  def resolve(fileName: String): ContentType = contentType
}

/**
 * Allows to customize one of the predefined routes of [[FileAndResourceRoute]] to respond
 * with a particular content type.
 *
 * The default behavior is to determine the content type by file extension.
 */
trait FileAndResourceRoute extends Route {
  /**
   * Returns a variant of this route that responds with the given constant [[akka.http.javadsl.model.ContentType]].
   */
  def withContentType(contentType: ContentType): Route

  /**
   * Returns a variant of this route that uses the specified [[ContentTypeResolver]] to determine
   * which [[akka.http.javadsl.model.ContentType]] to respond with by file name.
   */
  def resolveContentTypeWith(resolver: ContentTypeResolver): Route
}

object FileAndResourceRoute {
  /**
   * INTERNAL API
   */
  private[http] def apply(f: ContentTypeResolver ⇒ Route): FileAndResourceRoute =
    new FileAndResourceRouteWithDefaultResolver(f) with FileAndResourceRoute {
      def withContentType(contentType: ContentType): Route = resolveContentTypeWith(StaticContentTypeResolver(contentType))
      def resolveContentTypeWith(resolver: ContentTypeResolver): Route = f(resolver)
    }

  /**
   * INTERNAL API
   */
  private[http] def forFixedName(fileName: String)(f: ContentType ⇒ Route): FileAndResourceRoute =
    new FileAndResourceRouteWithDefaultResolver(resolver ⇒ f(resolver.resolve(fileName))) with FileAndResourceRoute {
      def withContentType(contentType: ContentType): Route = resolveContentTypeWith(StaticContentTypeResolver(contentType))
      def resolveContentTypeWith(resolver: ContentTypeResolver): Route = f(resolver.resolve(fileName))
    }
}

abstract class FileAndResourceDirectives extends ExecutionDirectives {
  /**
   * Completes GET requests with the content of the given resource loaded from the default ClassLoader.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String): Route =
    getFromResource(path, defaultClassLoader)

  /**
   * Completes GET requests with the content of the given resource loaded from the given ClassLoader.
   * If the resource cannot be found or read the Route rejects the request.
   */
  def getFromResource(path: String, classLoader: ClassLoader): Route =
    FileAndResourceRoute.forFixedName(path)(GetFromResource(path, _, classLoader))

  /**
   * Completes GET requests with the content from the resource identified by the given
   * directoryPath and the unmatched path.
   */
  def getFromResourceDirectory(directoryPath: String): FileAndResourceRoute =
    getFromResourceDirectory(directoryPath, defaultClassLoader)

  /**
   * Completes GET requests with the content from the resource identified by the given
   * directoryPath and the unmatched path from the given ClassLoader.
   */
  def getFromResourceDirectory(directoryPath: String, classLoader: ClassLoader): FileAndResourceRoute =
    FileAndResourceRoute(GetFromResourceDirectory(directoryPath, classLoader, _))

  /**
   * Completes GET requests with the content of the given file.
   */
  def getFromFile(file: File): FileAndResourceRoute = FileAndResourceRoute.forFixedName(file.getPath)(GetFromFile(file, _))

  /**
   * Completes GET requests with the content of the file at the path.
   */
  def getFromFile(path: String): FileAndResourceRoute = getFromFile(new File(path))

  /**
   * Completes GET requests with the content from the file identified by the given
   * directory and the unmatched path of the request.
   */
  def getFromDirectory(directory: File): FileAndResourceRoute = FileAndResourceRoute(GetFromDirectory(directory, browseable = false, _))

  /**
   * Completes GET requests with the content from the file identified by the given
   * directoryPath and the unmatched path of the request.
   */
  def getFromDirectory(directoryPath: String): FileAndResourceRoute = getFromDirectory(new File(directoryPath))

  /**
   * Same as [[#getFromDirectory]] but generates a listing of files if the path is a directory.
   */
  def getFromBrowseableDirectory(directory: File): FileAndResourceRoute = FileAndResourceRoute(GetFromDirectory(directory, browseable = true, _))

  /**
   * Same as [[#getFromDirectory]] but generates a listing of files if the path is a directory.
   */
  def getFromBrowseableDirectory(directoryPath: String): FileAndResourceRoute = FileAndResourceRoute(GetFromDirectory(new File(directoryPath), browseable = true, _))

  protected def defaultClassLoader: ClassLoader = server.directives.FileAndResourceDirectives.defaultClassLoader
}
