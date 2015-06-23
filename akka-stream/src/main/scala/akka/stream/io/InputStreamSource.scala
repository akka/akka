/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.InputStream

import akka.japi.function.Creator
import akka.stream.impl.io.InputStreamSource
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Source._
import akka.stream.{ Attributes, javadsl }
import akka.util.ByteString

import scala.concurrent.Future

object InputStreamSource {

  final val DefaultChunkSize = 8192
  final val DefaultAttributes = Attributes.name("inputStreamSource")

  /**
   * Creates a Source that will pull data out of the given input stream.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements.
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def apply(createInputStream: () ⇒ InputStream, chunkSize: Int = DefaultChunkSize): Source[ByteString, Future[Long]] =
    new Source(new InputStreamSource(createInputStream, chunkSize, DefaultAttributes, shape("InputStreamSource")))

  /**
   * Java API
   *
   * Creates a Source that will pull data out of the given input stream.
   * Emitted elements are [[ByteString]] elements, chunked by default by [[DefaultChunkSize]] bytes.
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def create(createInputStream: Creator[InputStream]): javadsl.Source[ByteString, Future[java.lang.Long]] =
    create(createInputStream, DefaultChunkSize)

  /**
   * Java API
   *
   * Creates a Source that will pull data out of the given input stream.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements.
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def create(createInputStream: Creator[InputStream], chunkSize: Int): javadsl.Source[ByteString, Future[java.lang.Long]] =
    apply(() ⇒ createInputStream.create(), chunkSize).asJava.asInstanceOf[javadsl.Source[ByteString, Future[java.lang.Long]]]

}
