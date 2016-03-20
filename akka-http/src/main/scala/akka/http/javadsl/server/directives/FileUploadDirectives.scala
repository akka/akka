/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.io.File
import java.util.function.BiFunction

import akka.http.impl.util.JavaMapping.Implicits._

import akka.http.javadsl.model.ContentType
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Source
import akka.util.ByteString

abstract class FileUploadDirectives extends FileAndResourceDirectives {
  /**
   * Streams the bytes of the file submitted using multipart with the given file name into a temporary file on disk.
   * If there is an error writing to disk the request will be failed with the thrown exception, if there is no such
   * field the request will be rejected, if there are multiple file parts with the same name, the first one will be
   * used and the subsequent ones ignored.
   */
  def uploadedFile(fieldName: String, inner: BiFunction[FileInfo, File, Route]): Route = RouteAdapter {
    D.uploadedFile(fieldName) { case (info, file) ⇒ inner.apply(info, file).delegate }
  }

  /**
   * Collects each body part that is a multipart file as a tuple containing metadata and a `Source`
   * for streaming the file contents somewhere. If there is no such field the request will be rejected,
   * if there are multiple file parts with the same name, the first one will be used and the subsequent
   * ones ignored.
   */
  def fileUpload(fieldName: String, inner: BiFunction[FileInfo, Source[ByteString, Any], Route]): Route = RouteAdapter {
    D.fileUpload(fieldName) { case (info, src) ⇒ inner.apply(info, src.asJava).delegate }
  }
}

/**
 * Additional metadata about the file being uploaded/that was uploaded using the [[FileUploadDirectives]]
 */
abstract class FileInfo {
  /**
   * Name of the form field the file was uploaded in
   */
  def getFieldName: String

  /**
   * User specified name of the uploaded file
   */
  def getFileName: String

  /**
   * Content type of the file
   */
  def getContentType: ContentType
}