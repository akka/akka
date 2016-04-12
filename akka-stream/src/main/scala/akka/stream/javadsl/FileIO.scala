/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import java.io.File
import java.nio.file.{ Path, StandardOpenOption }
import java.util
import akka.stream.{ scaladsl, javadsl }
import akka.stream.IOResult
import akka.util.ByteString
import java.util.concurrent.CompletionStage

import scala.collection.JavaConverters._

/**
 * Factories to create sinks and sources from files
 */
object FileIO {

  /**
   * Creates a Sink that writes incoming [[ByteString]] elements to the given file.
   * Overwrites existing files, if you want to append to an existing file use [[#file(Path, util.Set[StandardOpenOption])]].
   *
   * Materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f The file to write to
   */
  @deprecated("Use `toPath` instead.", "2.4.5")
  def toFile(f: File): javadsl.Sink[ByteString, CompletionStage[IOResult]] = toPath(f.toPath)

  /**
   * Creates a Sink that writes incoming [[ByteString]] elements to the given file.
   * Overwrites existing files, if you want to append to an existing file use [[#file(Path, util.Set[StandardOpenOption])]].
   *
   * Materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f The file to write to
   */
  def toPath(f: Path): javadsl.Sink[ByteString, CompletionStage[IOResult]] =
    new Sink(scaladsl.FileIO.toPath(f).toCompletionStage())

  /**
   * Creates a Sink that writes incoming [[ByteString]] elements to the given file.
   *
   * Materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f The file to write to
   * @param options File open options
   */
  @deprecated("Use `toPath` instead.", "2.4.5")
  def toFile(f: File, options: util.Set[StandardOpenOption]): javadsl.Sink[ByteString, CompletionStage[IOResult]] =
    toPath(f.toPath)

  /**
   * Creates a Sink that writes incoming [[ByteString]] elements to the given file.
   *
   * Materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f The file to write to
   * @param options File open options
   */
  def toPath(f: Path, options: util.Set[StandardOpenOption]): javadsl.Sink[ByteString, CompletionStage[IOResult]] =
    new Sink(scaladsl.FileIO.toPath(f, options.asScala.toSet).toCompletionStage())

  /**
   * Creates a Source from a files contents.
   * Emitted elements are [[ByteString]] elements, chunked by default by 8192 bytes,
   * except the last element, which will be up to 8192 in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   */
  @deprecated("Use `fromPath` instead.", "2.4.5")
  def fromFile(f: File): javadsl.Source[ByteString, CompletionStage[IOResult]] = fromPath(f.toPath)

  /**
   * Creates a Source from a files contents.
   * Emitted elements are [[ByteString]] elements, chunked by default by 8192 bytes,
   * except the last element, which will be up to 8192 in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   */
  def fromPath(f: Path): javadsl.Source[ByteString, CompletionStage[IOResult]] = fromPath(f, 8192)

  /**
   * Creates a synchronous Source from a files contents.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements,
   * except the last element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   */
  @deprecated("Use `fromPath` instead.", "2.4.5")
  def fromFile(f: File, chunkSize: Int): javadsl.Source[ByteString, CompletionStage[IOResult]] =
    fromPath(f.toPath, chunkSize)

  /**
   * Creates a synchronous Source from a files contents.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements,
   * except the last element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[java.util.concurrent.CompletionStage]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   */
  def fromPath(f: Path, chunkSize: Int): javadsl.Source[ByteString, CompletionStage[IOResult]] =
    new Source(scaladsl.FileIO.fromPath(f, chunkSize).toCompletionStage())
}
