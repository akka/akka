/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.scaladsl.Framing.FramingException
import akka.util.ByteString

import scala.annotation.switch

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.JsonFraming]] instead.
 */
private[akka] object JsonObjectParser {

  final val SquareBraceStart = '['.toByte
  final val SquareBraceEnd = ']'.toByte
  final val CurlyBraceStart = '{'.toByte
  final val CurlyBraceEnd = '}'.toByte
  final val DoubleQuote = '"'.toByte
  final val Backslash = '\\'.toByte
  final val Comma = ','.toByte

  final val LineBreak = '\n'.toByte
  final val LineBreak2 = '\r'.toByte
  final val Tab = '\t'.toByte
  final val Space = ' '.toByte

  final val Whitespace = Set(LineBreak, LineBreak2, Tab, Space)

  def isWhitespace(input: Byte): Boolean =
    Whitespace.contains(input)

}

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.JsonFraming]] instead.
 *
 * **Mutable** framing implementation that given any number of [[ByteString]] chunks, can emit JSON objects contained within them.
 * Typically JSON objects are separated by new-lines or commas, however a top-level JSON Array can also be understood and chunked up
 * into valid JSON objects by this framing implementation.
 *
 * Leading whitespace between elements will be trimmed.
 */
private[akka] class JsonObjectParser(maximumObjectLength: Int = Int.MaxValue) {
  import JsonObjectParser._

  private var buffer: ByteString = ByteString.empty

  private var pos = 0 // latest position of pointer while scanning for json object end
  private var trimFront = 0 // number of chars to drop from the front of the bytestring before emitting (skip whitespace etc)
  private var depth = 0 // counter of object-nesting depth, once hits 0 an object should be emitted

  private var charsInObject = 0
  private var completedObject = false
  private var inStringExpression = false
  private var isStartOfEscapeSequence = false
  private var lastInput = 0.toByte

  /**
   * Appends input ByteString to internal byte string buffer.
   * Use [[poll]] to extract contained JSON objects.
   */
  def offer(input: ByteString): Unit =
    buffer ++= input

  def isEmpty: Boolean = buffer.isEmpty

  /**
   * Attempt to locate next complete JSON object in buffered ByteString and returns `Some(it)` if found.
   * May throw a [[akka.stream.scaladsl.Framing.FramingException]] if the contained JSON is invalid or max object size is exceeded.
   */
  def poll(): Option[ByteString] = {
    val foundObject = seekObject()
    if (!foundObject) None
    else
      (pos: @switch) match {
        case -1 | 0 ⇒ None
        case _ ⇒
          val (emit, buf) = buffer.splitAt(pos)
          buffer = buf.compact
          pos = 0

          val tf = trimFront
          trimFront = 0

          if (tf == 0) Some(emit)
          else {
            val trimmed = emit.drop(tf)
            if (trimmed.isEmpty) None
            else Some(trimmed)
          }
      }
  }

  /** @return true if an entire valid JSON object was found, false otherwise */
  private def seekObject(): Boolean = {
    completedObject = false
    val bufSize = buffer.size
    while (pos != -1 && (pos < bufSize && pos < maximumObjectLength) && !completedObject)
      proceed(buffer(pos))

    if (pos >= maximumObjectLength)
      throw new FramingException(s"""JSON element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

    completedObject
  }

  private def proceed(input: Byte): Unit = {
    if (input == SquareBraceStart && outsideObject) {
      // outer object is an array
      pos += 1
      trimFront += 1
    } else if (input == SquareBraceEnd && outsideObject) {
      // outer array completed!
      pos = -1
    } else if (input == Comma && outsideObject) {
      // do nothing
      pos += 1
      trimFront += 1
    } else if (input == Backslash) {
      if (lastInput == Backslash) isStartOfEscapeSequence = false
      else isStartOfEscapeSequence = true
      pos += 1
    } else if (input == DoubleQuote) {
      if (!isStartOfEscapeSequence) inStringExpression = !inStringExpression
      isStartOfEscapeSequence = false
      pos += 1
    } else if (input == CurlyBraceStart && !inStringExpression) {
      isStartOfEscapeSequence = false
      depth += 1
      pos += 1
    } else if (input == CurlyBraceEnd && !inStringExpression) {
      isStartOfEscapeSequence = false
      depth -= 1
      pos += 1
      if (depth == 0) {
        charsInObject = 0
        completedObject = true
      }
    } else if (isWhitespace(input) && !inStringExpression) {
      pos += 1
      if (depth == 0) trimFront += 1
    } else if (insideObject) {
      isStartOfEscapeSequence = false
      pos += 1
    } else {
      throw new FramingException(s"Invalid JSON encountered at position [$pos] of [$buffer]")
    }

    lastInput = input
  }

  @inline private final def insideObject: Boolean =
    !outsideObject

  @inline private final def outsideObject: Boolean =
    depth == 0

}
