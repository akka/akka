/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import scala.language.implicitConversions

/**
 * Provides implicit conversions such that sources and sinks contained within `akka.stream.io`
 * as if they were defined on [[Source]] or [[Sink]] directly.
 *
 * Example:
 * {{{
 *   import akka.stream.scaladsl.Source
 *   import akka.stream.io._
 *
 *   // explicitly using IO Source:
 *   FileSource(file).map(...)
 *
 *   // using implicit conversion:
 *   import akka.stream.io.Implicits._
 *   Source.synchronousFile(file).map(...)
 * }}}
 */
object Implicits {

  // ---- Sources ----

  implicit final class AddInputStreamSource(val s: Source.type) extends AnyVal {
    def inputStream: InputStreamSource.type = InputStreamSource
  }

  // ---- Sinks ----

  implicit final class AddOutputStreamSink(val s: Sink.type) extends AnyVal {
    def outputStream: OutputStreamSink.type = OutputStreamSink
  }
}
