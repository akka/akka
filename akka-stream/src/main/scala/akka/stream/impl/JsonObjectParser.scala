/*
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.scaladsl.Framing.FramingException
import akka.util.ByteString

import scala.annotation.switch

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.JsonFraming]] instead.
 */
@InternalApi private[akka] object JsonObjectParser {

  final val SquareBraceStart = '['.toByte
  final val SquareBraceEnd = ']'.toByte
  final val CurlyBraceStart = '{'.toByte
  final val CurlyBraceEnd = '}'.toByte
  final val DoubleQuote = '"'.toByte
  final val Backslash = '\\'.toByte
  final val Comma = ','.toByte

  final val LineBreak = 10 // '\n'
  final val LineBreak2 = 13 // '\r'
  final val Tab = 9 // '\t'
  final val Space = 32 // ' '

  def isWhitespace(b: Byte): Boolean = (b: @switch) match {
    case Space      ⇒ true
    case LineBreak  ⇒ true
    case LineBreak2 ⇒ true
    case Tab        ⇒ true
    case _          ⇒ false
  }

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
@InternalApi private[akka] class JsonObjectParser(maximumObjectLength: Int = Int.MaxValue) {
  import JsonObjectParser._

  private var buffer: ByteString = ByteString.empty

  private var pos = 0 // latest position of pointer while scanning for json object end
  private var trimFront = 0 // number of chars to drop from the front of the bytestring before emitting (skip whitespace etc)
  private var depth = 0 // counter of object-nesting depth, once hits 0 an object should be emitted

  private var charsInObject = 0
  private var completedObject = false
  private var inStringExpression = false
  private var inNakedExpression = false
  private var pastExpression = false
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

          val cio = charsInObject
          charsInObject = 0

          val result = if (tf == 0) {
            val trimmed = emit.take(cio)
            if (trimmed.isEmpty) None
            else Some(trimmed)
          }
          else {
            val trimmed = emit.drop(tf).take(cio)
            if (trimmed.isEmpty) None
            else Some(trimmed)
          }
          println("result=", result.map(_.decodeString("UTF-8")))
          result
      }
  }

  /** @return true if an entire valid JSON object was found, false otherwise */
  private def seekObject(): Boolean = {
    completedObject = false

    val bufSize = buffer.size
    while (pos != -1 && (pos < bufSize && pos < maximumObjectLength) && !completedObject)
      proceed(buffer(pos))


    println(s"done proceeding; pos=${pos} bufSize=$bufSize pastEx=$pastExpression complete=$completedObject")
    if (pastExpression && (!completedObject)) {
      /* there may be a straggler object here — no problem */
      println("helping straggler object")
      completedObject = true
    }

    if (pos >= maximumObjectLength)
      throw new FramingException(s"""JSON element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

    completedObject
  }

  private def proceed(input: Byte): Unit = {
    println("input=",input,input.toChar, s" this=(pos: $pos, depth=$depth, trimFront=$trimFront, charsInObject=$charsInObject, soe=${isStartOfEscapeSequence} inNaked=$inNakedExpression pastEx=$pastExpression inString=$inStringExpression insideObj=$insideObject)")

    if (input == SquareBraceStart && outsideObjectOrString) {
      // outer object is an array
      pos += 1
      trimFront += 1
      println("—S1—")
    } else if (input == SquareBraceEnd && outsideObjectOrString) {
      // outer array completed!
      if (pastExpression) {
        completedObject = true
        println("—S2 A—")
      } else {
        pos = -1
        println("—S2 B—")
      }

    } else if ( ((input == Comma) || (input == LineBreak)) && outsideObjectOrString) {
      if ((!inNakedExpression) && (!pastExpression)) {
        // do nothing
        pos += 1
        trimFront += 1
        println("—S3 A—")
      } else {
        pos += 1 // leave charsInObject as is
        completedObject = true
        inNakedExpression = false
        pastExpression = false
        println("—S3 B—")
      }
    } else if (input == Backslash) {
      if (lastInput == Backslash & isStartOfEscapeSequence) isStartOfEscapeSequence = false
      else isStartOfEscapeSequence = true
      pos += 1
      charsInObject += 1
      println("—S4—")
    } else if ((input == DoubleQuote) && isStartOfEscapeSequence && inStringExpression) {
      isStartOfEscapeSequence = false
      pos += 1
      charsInObject += 1
      println("—S5 A—")
    } else if ((input == DoubleQuote) && (!isStartOfEscapeSequence) && (!inStringExpression) && !pastExpression && !inNakedExpression)  {
      // we can start that whether we are insideObject or outsideObject
      inStringExpression = true
      pos += 1
      charsInObject += 1
      println("—S5 B—")
    } else if ((input == DoubleQuote) && (!isStartOfEscapeSequence) && inStringExpression) {
      inStringExpression = false
      pos += 1
      charsInObject += 1
      if (outsideObject) {
        pastExpression = true
        // if we're insideObject, we're not pastExpression here; in fact we may find additional strings soon.
      }
      println("—S5 C—")

      /* note:
      - DoubleQuote && !inStringExpression && inNakedExpression is malformed
      - DoubleQuote && !inStringExpression && pastExpression is malformed
      - DoubleQuote && isStartOfEscapeSequence && !inStringExpression is malformed
       */

    } else if ((input != DoubleQuote) && inStringExpression) {
      // we're in the string, let's accumulate
      pos += 1
      charsInObject += 1
      println("—Sx2—")
    }
    else if (input == CurlyBraceStart && !inStringExpression && !inNakedExpression) {
      isStartOfEscapeSequence = false
      depth += 1
      pos += 1
      charsInObject += 1
      println("—S6—")
    } else if (input == CurlyBraceEnd && !inStringExpression && !inNakedExpression) {
      isStartOfEscapeSequence = false
      depth -= 1
      pos += 1
      charsInObject += 1
      if (depth == 0) {
        // we're ABOUT to call it completed, but we'll wait
        pastExpression = true
        println("—S7 C—")
      } else {
        println("—S7—")
      }
    } else if (isWhitespace(input) && !inStringExpression && !inNakedExpression && (depth == 0)) {
      pos += 1
      if (depth == 0) {
        if (!pastExpression) trimFront += 1 // if inNakedExpression we need to charsInObject +=1 but we can't be here.
        println("—S8 A—")
      } else {
        charsInObject += 1
        println("—S8 B—")
      }
    } else if (insideObject) {
      isStartOfEscapeSequence = false
      pos += 1
      charsInObject += 1
      println("—S9—")
    } else if (!isWhitespace(input) && !inNakedExpression && outsideObject && !inStringExpression && !pastExpression) {
      inNakedExpression = true
      charsInObject += 1
      pos += 1
      println("—S10—")
    } else if (!isWhitespace(input) && inNakedExpression && outsideObject && !inStringExpression && !pastExpression) {
      pos += 1
      charsInObject += 1
      println("—S11—")
    } else if (isWhitespace(input) && inNakedExpression && outsideObject && !inStringExpression && !pastExpression) {
      pastExpression = true
      inNakedExpression = false
      pos += 1
      println("—S12—")
    } else if (isWhitespace(input) && inNakedExpression && outsideObject && !inStringExpression && pastExpression) {
      /* skip whitespace after naked constant */
      pos += 1
      println("—S13—")
    } else {
      throw new FramingException(s"Invalid JSON encountered at position [$pos] of [$buffer]")
    }

    lastInput = input
  }

  @inline private final def insideObject: Boolean =
    !outsideObject

  @inline private final def outsideObject: Boolean =
    (depth == 0)

  @inline private final def outsideObjectOrString: Boolean =
    (depth == 0) && (!inStringExpression) && (!isStartOfEscapeSequence)
}
