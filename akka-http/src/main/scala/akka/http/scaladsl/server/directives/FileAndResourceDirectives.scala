/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import java.io.File
import java.net.{ URI, URL }

import akka.http.javadsl.model
import akka.http.javadsl.model.RequestEntity
import akka.stream.ActorAttributes
import akka.stream.scaladsl.{ FileIO, StreamConverters }

import scala.annotation.tailrec
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshalling.{ Marshaller, Marshalling, ToEntityMarshaller }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._
import akka.http.javadsl

import scala.collection.JavaConverters._
import JavaMapping.Implicits._
import akka.http.javadsl.server.RoutingJavaMapping

/**
 * @groupname fileandresource File and resource directives
 * @groupprio fileandresource 70
 */
trait FileAndResourceDirectives {
  import CacheConditionDirectives._
  import MethodDirectives._
  import FileAndResourceDirectives._
  import RouteDirectives._
  import BasicDirectives._
  import RouteConcatenation._

  /**
   * Completes GET requests with the content of the given file.
   * If the file cannot be found or read the request is rejected.
   *
   * @group fileandresource
   */
  def getFromFile(fileName: String)(implicit resolver: ContentTypeResolver): Route =
    getFromFile(new File(fileName))

  /**
   * Completes GET requests with the content of the given file.
   * If the file cannot be found or read the request is rejected.
   *
   * @group fileandresource
   */
  def getFromFile(file: File)(implicit resolver: ContentTypeResolver): Route =
    getFromFile(file, resolver(file.getName))

  /**
   * Completes GET requests with the content of the given file.
   * If the file cannot be found or read the request is rejected.
   *
   * @group fileandresource
   */
  def getFromFile(file: File, contentType: ContentType): Route =
    get {
      if (file.isFile && file.canRead)
        conditionalFor(file.length, file.lastModified) {
          if (file.length > 0) {
            withRangeSupportAndPrecompressedMediaTypeSupportAndExtractSettings { settings ⇒
              complete {
                HttpEntity.Default(contentType, file.length,
                  FileIO.fromFile(file).withAttributes(ActorAttributes.dispatcher(settings.fileIODispatcher)))
              }
            }
          } else complete(HttpEntity.Empty)
        }
      else reject
    }

  private def conditionalFor(length: Long, lastModified: Long): Directive0 =
    extractSettings.flatMap(settings ⇒
      if (settings.fileGetConditional) {
        val tag = java.lang.Long.toHexString(lastModified ^ java.lang.Long.reverse(length))
        val lastModifiedDateTime = DateTime(math.min(lastModified, System.currentTimeMillis))
        conditional(EntityTag(tag), lastModifiedDateTime)
      } else pass)

  /**
   * Completes GET requests with the content of the given class-path resource.
   * If the resource cannot be found or read the Route rejects the request.
   *
   * @group fileandresource
   */
  def getFromResource(resourceName: String)(implicit resolver: ContentTypeResolver): Route =
    getFromResource(resourceName, resolver(resourceName))

  /**
   * Completes GET requests with the content of the given resource.
   * If the resource is a directory or cannot be found or read the Route rejects the request.
   *
   * @group fileandresource
   */
  def getFromResource(resourceName: String, contentType: ContentType, classLoader: ClassLoader = _defaultClassLoader): Route =
    if (!resourceName.endsWith("/"))
      get {
        Option(classLoader.getResource(resourceName)) flatMap ResourceFile.apply match {
          case Some(ResourceFile(url, length, lastModified)) ⇒
            conditionalFor(length, lastModified) {
              if (length > 0) {
                withRangeSupportAndPrecompressedMediaTypeSupportAndExtractSettings { settings ⇒
                  complete {
                    HttpEntity.Default(contentType, length,
                      StreamConverters.fromInputStream(() ⇒ url.openStream())
                        .withAttributes(ActorAttributes.dispatcher(settings.fileIODispatcher))) // TODO is this needed? It already uses `val inputStreamSource = name("inputStreamSource") and IODispatcher`
                  }
                }
              } else complete(HttpEntity.Empty)
            }
          case _ ⇒ reject // not found or directory
        }
      }
    else reject // don't serve the content of resource "directories"

  /**
   * Completes GET requests with the content of a file underneath the given directory.
   * If the file cannot be read the Route rejects the request.
   *
   * @group fileandresource
   */
  def getFromDirectory(directoryName: String)(implicit resolver: ContentTypeResolver): Route = {
    val base = withTrailingSlash(directoryName)
    extractUnmatchedPath { path ⇒
      extractLog { log ⇒
        fileSystemPath(base, path, log) match {
          case ""       ⇒ reject
          case fileName ⇒ getFromFile(fileName)
        }
      }
    }
  }

  /**
   * Completes GET requests with a unified listing of the contents of all given directories.
   * The actual rendering of the directory contents is performed by the in-scope `Marshaller[DirectoryListing]`.
   *
   * @group fileandresource
   */
  def listDirectoryContents(directories: String*)(implicit renderer: DirectoryRenderer): Route =
    get {
      extractRequestContext { ctx ⇒
        val path = ctx.unmatchedPath
        val fullPath = ctx.request.uri.path.toString
        val matchedLength = fullPath.lastIndexOf(path.toString)
        require(matchedLength >= 0)
        val pathPrefix = fullPath.substring(0, matchedLength)
        val pathString = withTrailingSlash(fileSystemPath("/", path, ctx.log, '/'))
        val dirs = directories flatMap { dir ⇒
          fileSystemPath(withTrailingSlash(dir), path, ctx.log) match {
            case "" ⇒ None
            case fileName ⇒
              val file = new File(fileName)
              if (file.isDirectory && file.canRead) Some(file) else None
          }
        }
        implicit val marshaller: ToEntityMarshaller[DirectoryListing] = renderer.marshaller(ctx.settings.renderVanityFooter)

        if (dirs.isEmpty) reject
        else complete(DirectoryListing(pathPrefix + pathString, isRoot = pathString == "/", dirs.flatMap(_.listFiles)))
      }
    }

  /**
   * Same as `getFromBrowseableDirectories` with only one directory.
   *
   * @group fileandresource
   */
  def getFromBrowseableDirectory(directory: String)(implicit renderer: DirectoryRenderer, resolver: ContentTypeResolver): Route =
    getFromBrowseableDirectories(directory)

  /**
   * Serves the content of the given directories as a file system browser, i.e. files are sent and directories
   * served as browseable listings.
   *
   * @group fileandresource
   */
  def getFromBrowseableDirectories(directories: String*)(implicit renderer: DirectoryRenderer, resolver: ContentTypeResolver): Route = {
    directories.map(getFromDirectory).reduceLeft(_ ~ _) ~ listDirectoryContents(directories: _*)
  }

  /**
   * Same as "getFromDirectory" except that the file is not fetched from the file system but rather from a
   * "resource directory".
   * If the requested resource is itself a directory or cannot be found or read the Route rejects the request.
   *
   * @group fileandresource
   */
  def getFromResourceDirectory(directoryName: String, classLoader: ClassLoader = _defaultClassLoader)(implicit resolver: ContentTypeResolver): Route = {
    val base = if (directoryName.isEmpty) "" else withTrailingSlash(directoryName)

    extractUnmatchedPath { path ⇒
      extractLog { log ⇒
        fileSystemPath(base, path, log, separator = '/') match {
          case ""           ⇒ reject
          case resourceName ⇒ getFromResource(resourceName, resolver(resourceName), classLoader)
        }
      }
    }
  }

  protected[http] def _defaultClassLoader: ClassLoader = classOf[ActorSystem].getClassLoader
}

object FileAndResourceDirectives extends FileAndResourceDirectives {
  private val withRangeSupportAndPrecompressedMediaTypeSupportAndExtractSettings =
    RangeDirectives.withRangeSupport &
      CodingDirectives.withPrecompressedMediaTypeSupport &
      BasicDirectives.extractSettings

  private def withTrailingSlash(path: String): String = if (path endsWith "/") path else path + '/'
  private def fileSystemPath(base: String, path: Uri.Path, log: LoggingAdapter, separator: Char = File.separatorChar): String = {
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

  object ResourceFile {
    def apply(url: URL): Option[ResourceFile] = url.getProtocol match {
      case "file" ⇒
        val file = new File(url.toURI)
        if (file.isDirectory) None
        else Some(ResourceFile(url, file.length(), file.lastModified()))
      case "jar" ⇒
        val path = new URI(url.getPath).getPath // remove "file:" prefix and normalize whitespace
        val bangIndex = path.indexOf('!')
        val filePath = path.substring(0, bangIndex)
        val resourcePath = path.substring(bangIndex + 2)
        val jar = new java.util.zip.ZipFile(filePath)
        try {
          val entry = jar.getEntry(resourcePath)
          Option(jar.getInputStream(entry)) map { is ⇒
            is.close()
            ResourceFile(url, entry.getSize, entry.getTime)
          }
        } finally jar.close()
      case _ ⇒
        val conn = url.openConnection()
        try {
          conn.setUseCaches(false) // otherwise the JDK will keep the connection open when we close!
          val len = conn.getContentLength
          val lm = conn.getLastModified
          Some(ResourceFile(url, len, lm))
        } finally conn.getInputStream.close()
    }
  }
  case class ResourceFile(url: URL, length: Long, lastModified: Long)

  trait DirectoryRenderer extends akka.http.javadsl.server.directives.DirectoryRenderer {
    type JDL = akka.http.javadsl.server.directives.DirectoryListing
    type SDL = akka.http.scaladsl.server.directives.DirectoryListing
    type SRE = akka.http.scaladsl.model.RequestEntity
    type JRE = akka.http.javadsl.model.RequestEntity

    def marshaller(renderVanityFooter: Boolean): ToEntityMarshaller[DirectoryListing]

    final override def directoryMarshaller(renderVanityFooter: Boolean): akka.http.javadsl.server.Marshaller[JDL, JRE] = {
      val combined = Marshaller.combined[JDL, SDL, SRE](x ⇒ JavaMapping.toScala(x)(RoutingJavaMapping.convertDirectoryListing))(marshaller(renderVanityFooter))
        .map(_.asJava)
      akka.http.javadsl.server.Marshaller.fromScala(combined)
    }

  }
  trait LowLevelDirectoryRenderer {
    implicit def defaultDirectoryRenderer: DirectoryRenderer =
      new DirectoryRenderer {
        def marshaller(renderVanityFooter: Boolean): ToEntityMarshaller[DirectoryListing] =
          DirectoryListing.directoryMarshaller(renderVanityFooter)
      }
  }
  object DirectoryRenderer extends LowLevelDirectoryRenderer {
    implicit def liftMarshaller(implicit _marshaller: ToEntityMarshaller[DirectoryListing]): DirectoryRenderer =
      new DirectoryRenderer {
        def marshaller(renderVanityFooter: Boolean): ToEntityMarshaller[DirectoryListing] = _marshaller
      }
  }
}

trait ContentTypeResolver extends akka.http.javadsl.server.directives.ContentTypeResolver {
  def apply(fileName: String): ContentType
  final override def resolve(fileName: String): model.ContentType = apply(fileName)
}

object ContentTypeResolver {

  /**
   * The default way of resolving a filename to a ContentType is by looking up the file extension in the
   * registry of all defined media-types. By default all non-binary file content is assumed to be UTF-8 encoded.
   */
  implicit val Default: ContentTypeResolver = withDefaultCharset(HttpCharsets.`UTF-8`)

  def withDefaultCharset(charset: HttpCharset): ContentTypeResolver =
    new ContentTypeResolver {
      def apply(fileName: String) = {
        val lastDotIx = fileName.lastIndexOf('.')
        val mediaType = if (lastDotIx >= 0) {
          fileName.substring(lastDotIx + 1) match {
            case "gz" ⇒ fileName.lastIndexOf('.', lastDotIx - 1) match {
              case -1 ⇒ MediaTypes.`application/octet-stream`
              case x  ⇒ MediaTypes.forExtension(fileName.substring(x + 1, lastDotIx)).withComp(MediaType.Gzipped)
            }
            case ext ⇒ MediaTypes.forExtension(ext)
          }
        } else MediaTypes.`application/octet-stream`
        ContentType(mediaType, () ⇒ charset)
      }
    }

  def apply(f: String ⇒ ContentType): ContentTypeResolver =
    new ContentTypeResolver {
      def apply(fileName: String): ContentType = f(fileName)
    }
}

final case class DirectoryListing(path: String, isRoot: Boolean, files: Seq[File]) extends javadsl.server.directives.DirectoryListing {
  override def getPath: String = path
  override def getFiles: java.util.List[File] = files.asJava
}

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
      |<small>rendered by <a href="http://akka.io">Akka Http</a> on $</small>
      |</div>$
      |</body>
      |</html>
      |""".stripMarginWithNewline("\n") split '$'

  def directoryMarshaller(renderVanityFooter: Boolean): ToEntityMarshaller[DirectoryListing] =
    Marshaller.StringMarshaller.wrapWithEC(MediaTypes.`text/html`) { implicit ec ⇒ listing ⇒
      val DirectoryListing(path, isRoot, files) = listing
      val filesAndNames = files.map(file ⇒ file → file.getName).sortBy(_._2)
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
        val size = akka.http.impl.util.humanReadableByteCount(file.length, si = true)
        start(name).append("        ").append(lastModified(file))
        sb.append("                ".substring(size.length)).append(size).append('\n')
      }
      for ((file, name) ← directoryFilesAndNames) renderDirectory(file, name)
      for ((file, name) ← fileFilesAndNames) renderFile(file, name)
      if (isRoot && files.isEmpty) sb.append("(no files)\n")
      sb.append(html(3))
      if (renderVanityFooter) sb.append(html(4)).append(DateTime.now.toIsoLikeDateTimeString).append(html(5))
      sb.append(html(6)).toString
    }
}
