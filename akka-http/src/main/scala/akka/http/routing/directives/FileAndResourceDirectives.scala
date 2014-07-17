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

package akka.http.routing
package directives

import java.io.File
import scala.annotation.tailrec
import akka.actor.ActorRefFactory
import akka.http.marshalling.Marshaller
import akka.http.util._
import akka.http.model._
import headers._

import scala.concurrent.ExecutionContext

/* format: OFF */
trait FileAndResourceDirectives {
  import CacheConditionDirectives._
  import ExecutionDirectives._
  import MethodDirectives._
  import RangeDirectives._
  import RespondWithDirectives._
  import RouteDirectives._
  import MiscDirectives._
  import FileAndResourceDirectives._

  /**
   * Completes GET requests with the content of the given file. The actual I/O operation is
   * running detached in a `Future`, so it doesn't block the current thread (but potentially
   * some other thread !). If the file cannot be found or read the request is rejected.
   */
  def getFromFile(fileName: String)
                 (implicit settings: RoutingSettings, resolver: ContentTypeResolver, refFactory: ActorRefFactory): Route =
    getFromFile(new File(fileName))

  /**
   * Completes GET requests with the content of the given file. The actual I/O operation is
   * running detached in a `Future`, so it doesn't block the current thread (but potentially
   * some other thread !). If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File)
                 (implicit settings: RoutingSettings, resolver: ContentTypeResolver, refFactory: ActorRefFactory): Route =
    getFromFile(file, resolver(file.getName))

  /**
   * Completes GET requests with the content of the given file. The actual I/O operation is
   * running detached in a `Future`, so it doesn't block the current thread (but potentially
   * some other thread !). If the file cannot be found or read the request is rejected.
   */
  def getFromFile(file: File, contentType: ContentType)
                 (implicit settings: RoutingSettings, refFactory: ActorRefFactory): Route = FIXME
    /*get {
      detach() {
        if (file.isFile && file.canRead) {
          autoChunked.apply {
            conditionalFor(file.length, file.lastModified).apply {
              withRangeSupport() {
                complete(HttpEntity(contentType, HttpData(file)))
              }
            }
          }
        } else reject
      }
    }*/

  private def autoChunked(implicit settings: RoutingSettings, refFactory: ActorRefFactory): Directive0 =
    ???//autoChunk(settings.fileChunkingThresholdSize, settings.fileChunkingChunkSize)

  private def conditionalFor(length: Long, lastModified: Long)(implicit settings: RoutingSettings, ec: ExecutionContext): Directive0 =
    if (settings.fileGetConditional) {
      val tag = java.lang.Long.toHexString(lastModified ^ java.lang.Long.reverse(length))
      val lastModifiedDateTime = DateTime(math.min(lastModified, System.currentTimeMillis))
      conditional(EntityTag(tag), lastModifiedDateTime)
    } else BasicDirectives.noop

  /**
   * Adds a Last-Modified header to all HttpResponses from its inner Route.
   */
  def respondWithLastModifiedHeader(timestamp: Long): Directive0 = // TODO: remove when migrating to akka-http
    respondWithHeader(`Last-Modified`(DateTime(math.min(timestamp, System.currentTimeMillis))))

  /**
   * Completes GET requests with the content of the given resource. The actual I/O operation is
   * running detached in a `Future`, so it doesn't block the current thread (but potentially
   * some other thread !).
   * If the file cannot be found or read the Route rejects the request.
   */
  def getFromResource(resourceName: String)
                     (implicit resolver: ContentTypeResolver, refFactory: ActorRefFactory): Route =
    getFromResource(resourceName, resolver(resourceName))

  /**
   * Completes GET requests with the content of the given resource. The actual I/O operation is
   * running detached in a `Future`, so it doesn't block the current thread (but potentially
   * some other thread !).
   * If the file cannot be found or read the Route rejects the request.
   */
  def getFromResource(resourceName: String, contentType: ContentType)
                     (implicit refFactory: ActorRefFactory): Route = FIXME
    /*if (!resourceName.endsWith("/"))
      get {
        detach() {
          val theClassLoader = actorSystem(refFactory).dynamicAccess.classLoader
          theClassLoader.getResource(resourceName) match {
            case null ⇒ reject
            case url ⇒
              val (length, lastModified) = {
                val conn = url.openConnection()
                try {
                  conn.setUseCaches(false) // otherwise the JDK will keep the JAR file open when we close!
                  val len = conn.getContentLength
                  val lm = conn.getLastModified
                  len -> lm
                } finally { conn.getInputStream.close() }
              }
              implicit val bufferMarshaller = BasicMarshallers.byteArrayMarshaller(contentType)
              autoChunked.apply { // TODO: add implicit RoutingSettings to method and use here
                conditionalFor(length, lastModified).apply {
                  withRangeSupport() {
                    complete {
                      // readAllBytes closes the InputStream when done, which ends up closing the JAR file
                      // if caching has been disabled on the connection
                      val is = url.openStream()
                      try { FileUtils.readAllBytes(is) }
                      finally { is.close() }
                    }
                  }
                }
              }
          }
        }
      }
    else reject // don't serve the content of resource "directories"*/

  /**
   * Completes GET requests with the content of a file underneath the given directory.
   * The unmatchedPath of the [[spray.RequestContext]] is first transformed by the given pathRewriter function before
   * being appended to the given directoryName to build the final fileName.
   * The actual I/O operation is running detached in a `Future`, so it doesn't block the
   * current thread. If the file cannot be read the Route rejects the request.
   */
  def getFromDirectory(directoryName: String)
                      (implicit settings: RoutingSettings, resolver: ContentTypeResolver,
                       refFactory: ActorRefFactory, log: LoggingContext): Route = {
    val base = withTrailingSlash(directoryName)
    unmatchedPath { path ⇒
      fileSystemPath(base, path) match {
        case ""       ⇒ reject
        case fileName ⇒ getFromFile(fileName)
      }
    }
  }

  /**
   * Completes GET requests with a unified listing of the contents of all given directories.
   * The actual rendering of the directory contents is performed by the in-scope `Marshaller[DirectoryListing]`.
   */
  def listDirectoryContents(directories: String*)
                           (implicit renderer: Marshaller[DirectoryListing], refFactory: ActorRefFactory,
                            log: LoggingContext, ec: ExecutionContext): Route =
    (get & detach()) {
      unmatchedPath { path ⇒
        requestUri { fullUri ⇒
          val fullPath = fullUri.path.toString
          val matchedLength = fullPath.lastIndexOf(path.toString)
          require(matchedLength >= 0)
          val pathPrefix = fullPath.substring(0, matchedLength)
          val pathString = withTrailingSlash(fileSystemPath("/", path, '/'))
          val dirs = directories flatMap { dir ⇒
            fileSystemPath(withTrailingSlash(dir), path) match {
              case "" ⇒ None
              case fileName ⇒
                val file = new File(fileName)
                if (file.isDirectory && file.canRead) Some(file) else None
            }
          }
          if (dirs.isEmpty) reject
          else complete(DirectoryListing(pathPrefix + pathString, isRoot = pathString == "/", dirs.flatMap(_.listFiles)))
        }
      }
    }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   */
  def getFromBrowseableDirectory(directory: String)
                                (implicit renderer: Marshaller[DirectoryListing], settings: RoutingSettings,
                                 resolver: ContentTypeResolver, refFactory: ActorRefFactory, log: LoggingContext, ec: ExecutionContext): Route =
    getFromBrowseableDirectories(directory)

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browsable listings.
   */
  def getFromBrowseableDirectories(directories: String*)
                                  (implicit renderer: Marshaller[DirectoryListing], settings: RoutingSettings,
                                   resolver: ContentTypeResolver, refFactory: ActorRefFactory, log: LoggingContext, ec: ExecutionContext): Route = {
    import RouteConcatenation._
    directories.map(getFromDirectory).reduceLeft(_ ~ _) ~ listDirectoryContents(directories: _*)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory".
   */
  def getFromResourceDirectory(directoryName: String)
                              (implicit resolver: ContentTypeResolver, refFactory: ActorRefFactory, log: LoggingContext): Route = {
    val base = if (directoryName.isEmpty) "" else withTrailingSlash(directoryName)
    unmatchedPath { path ⇒
      fileSystemPath(base, path, separator = '/') match {
        case ""           ⇒ reject
        case resourceName ⇒ getFromResource(resourceName)
      }
    }
  }
}

/* format: ON */

object FileAndResourceDirectives extends FileAndResourceDirectives {
  private def withTrailingSlash(path: String): String = if (path endsWith "/") path else path + '/'
  private def fileSystemPath(base: String, path: Uri.Path, separator: Char = File.separatorChar)(implicit log: LoggingContext): String = {
    import java.lang.StringBuilder
    @tailrec def rec(p: Uri.Path, result: StringBuilder = new StringBuilder(base)): String =
      p match {
        case Uri.Path.Empty       ⇒ result.toString
        case Uri.Path.Slash(tail) ⇒ rec(tail, result.append(separator))
        case Uri.Path.Segment(head, tail) ⇒
          if (head.indexOf('/') >= 0 || head == "..") {
            log.warning("File-system path for base [{}] and Uri.Path [{}] contains suspicious path segment [{}], " +
              "GET access was disallowed", base, path, head)
            ""
          } else rec(tail, result.append(head))
      }
    rec(if (path.startsWithSlash) path.tail else path)
  }
}

trait ContentTypeResolver {
  def apply(fileName: String): ContentType
}

object ContentTypeResolver {

  /**
   * The default way of resolving a filename to a ContentType is by looking up the file extension in the
   * registry of all defined media-types. By default all non-binary file content is assumed to be UTF-8 encoded.
   */
  implicit val Default = withDefaultCharset(HttpCharsets.`UTF-8`)

  def withDefaultCharset(charset: HttpCharset): ContentTypeResolver =
    new ContentTypeResolver {
      def apply(fileName: String) = {
        val ext = fileName.lastIndexOf('.') match {
          case -1 ⇒ ""
          case x  ⇒ fileName.substring(x + 1)
        }
        val mediaType = MediaTypes.forExtension(ext) getOrElse MediaTypes.`application/octet-stream`
        mediaType match {
          case x if !x.binary ⇒ ContentType(x, charset)
          case x              ⇒ ContentType(x)
        }
      }
    }
}

case class DirectoryListing(path: String, isRoot: Boolean, files: Seq[File])

object DirectoryListing {

  private val html =
    """<html>
      |<head><title>Index of $</title></head>
      |<body>
      |<h1>Index of $</h1>
      |<hr>
      |<pre>
      |$</pre>
      |<hr>$
      |<div style="width:100%;text-align:right;color:gray">
      |<small>rendered by <a href="http://spray.io">spray</a> on $</small>
      |</div>$
      |</body>
      |</html>
      |""".stripMarginWithNewline("\n") split '$'

  implicit def DefaultMarshaller(implicit settings: RoutingSettings): Marshaller[DirectoryListing] = FIXME
  /*Marshaller.delegate[DirectoryListing, String](MediaTypes.`text/html`) { listing ⇒
      val DirectoryListing(path, isRoot, files) = listing
      val filesAndNames = files.map(file ⇒ file -> file.getName).sortBy(_._2)
      val deduped = filesAndNames.zipWithIndex.flatMap {
        case (fan @ (file, name), ix) ⇒
          if (ix == 0 || filesAndNames(ix - 1)._2 != name) Some(fan) else None
      }
      val (directoryFilesAndNames, fileFilesAndNames) = deduped.partition(_._1.isDirectory)
      def maxNameLength(seq: Seq[(File, String)]) = if (seq.isEmpty) 0 else seq.map(_._2.length).max
      val maxNameLen = math.max(maxNameLength(directoryFilesAndNames) + 1, maxNameLength(fileFilesAndNames))
      val sb = new java.lang.StringBuilder
      sb.append(html(0)).append(path).append(html(1)).append(path).append(html(2))
      if (!isRoot) {
        val secondToLastSlash = path.lastIndexOf('/', path.lastIndexOf('/', path.length - 1) - 1)
        sb.append("<a href=\"%s/\">../</a>\n" format path.substring(0, secondToLastSlash))
      }
      def lastModified(file: File) = DateTime(file.lastModified).toIsoLikeDateTimeString
      def start(name: String) =
        sb.append("<a href=\"").append(path + name).append("\">").append(name).append("</a>")
          .append(" " * (maxNameLen - name.length))
      def renderDirectory(file: File, name: String) =
        start(name + '/').append("        ").append(lastModified(file)).append('\n')
      def renderFile(file: File, name: String) = {
        val size = Utils.humanReadableByteCount(file.length, si = true)
        start(name).append("        ").append(lastModified(file))
        sb.append("                ".substring(size.length)).append(size).append('\n')
      }
      for ((file, name) ← directoryFilesAndNames) renderDirectory(file, name)
      for ((file, name) ← fileFilesAndNames) renderFile(file, name)
      if (isRoot && files.isEmpty) sb.append("(no files)\n")
      sb.append(html(3))
      if (settings.renderVanityFooter) sb.append(html(4)).append(DateTime.now.toIsoLikeDateTimeString).append(html(5))
      sb.append(html(6)).toString
    }*/
}
