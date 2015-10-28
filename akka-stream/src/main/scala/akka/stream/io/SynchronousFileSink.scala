/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.File

import akka.stream.impl.io.SynchronousFileSink
import akka.stream.{ Attributes, javadsl, ActorAttributes }
import akka.stream.scaladsl.Sink
import akka.util.ByteString

import scala.concurrent.Future

/**
 * Sink which writes incoming [[ByteString]]s to the given file
 */
object SynchronousFileSink {

  final val DefaultAttributes = Attributes.name("synchronousFileSink")

  /**
   * Synchronous (Java 6 compatible) Sink that writes incoming [[ByteString]] elements to the given file.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def apply(f: File, append: Boolean = false): Sink[ByteString, Future[Long]] =
    new Sink(new SynchronousFileSink(f, append, DefaultAttributes, Sink.shape("SynchronousFileSink")))

  /**
   * Java API
   *
   * Synchronous (Java 6 compatible) Sink that writes incoming [[ByteString]] elements to the given file.
   * Overwrites existing files, if you want to append to an existing file use [[#create(File, Boolean)]] instead.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def create(f: File): javadsl.Sink[ByteString, Future[java.lang.Long]] =
    apply(f, append = false).asJava.asInstanceOf[javadsl.Sink[ByteString, Future[java.lang.Long]]]

  /**
   * Java API
   *
   * Synchronous (Java 6 compatible) Sink that writes incoming [[ByteString]] elements to the given file.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def appendTo(f: File): javadsl.Sink[ByteString, Future[java.lang.Long]] =
    apply(f, append = true).asInstanceOf[javadsl.Sink[ByteString, Future[java.lang.Long]]]

}
