/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import java.nio.{ CharBuffer, ByteBuffer }
import java.util.Arrays.copyOf
import java.lang.{ StringBuilder ⇒ JStringBuilder }
import scala.annotation.tailrec
import akka.parboiled2.CharUtils
import akka.util.ByteString
import akka.http.impl.util._
import akka.http.scaladsl.model.{ IllegalHeaderException, StatusCodes, HttpHeader, ErrorInfo }
import akka.http.scaladsl.model.headers.{ EmptyHeader, RawHeader }
import akka.http.impl.model.parser.HeaderParser
import akka.http.impl.model.parser.CharacterClasses._

/**
 * INTERNAL API
 *
 * Provides for time- and space-efficient parsing of an HTTP header line in an HTTP message.
 * It keeps a cache of all headers encountered in a previous request, so as to avoid reparsing and recreation of header
 * model instances.
 * For the life-time of one HTTP connection an instance of this class is owned by the connection, i.e. not shared
 * with other connections. After the connection is closed it may be used by subsequent connections.
 *
 * The core of this parser/cache is a mutable space-efficient ternary trie (prefix tree) structure, whose data are
 * split across three arrays. The tree supports node addition and update, but no deletion (i.e. we never remove
 * entries).
 *
 * The `nodes` array keeps the main data of the trie nodes. One node is formed of a char (2 bytes) of which the
 * LSB (least significant byte) is an ASCII octet and the MSB either 0 or an index into the `branchData` array.
 * There are three types of nodes:
 *
 * 1. Simple nodes: non-branching, non-leaf nodes (the most frequent kind of node) have an MSB of zero and exactly
 *    one logical sub-node which is the next node in the `nodes` array (i.e. the one at index n + 1).
 * 2. Branching nodes: have 2 or three children and a non-zero MSB. (MSB value - 1)*3 is the row index into the
 *    `branchData` array, which contains the branching data.
 * 3. Leaf nodes: have no sub-nodes and no character value. They merely "stop" the node chain and point to a value.
 *    The LSB of leaf nodes is zero and the (MSB value - 1) is an index into the values array.
 *
 * This design has the following consequences:
 *  - Since only leaf nodes can have values the trie cannot store keys that are prefixes of other stored keys.
 *  - If the trie stores n values it has less than n branching nodes (adding the first value does not create a
 *    branching node, the addition of every subsequent value creates at most one additional branching node).
 *  - If the trie has n branching nodes it stores at least n * 2 and at most n * 3 values.
 *
 * The `branchData` array keeps the branching data for branching nodes in the trie.
 * It's a flattened two-dimensional array with a row consisting of the following 3 signed 16-bit integers:
 * row-index + 0: if non-zero: index into the `nodes` array for the "lesser" child of the node
 * row-index + 1: if non-zero: index into the `nodes` array for the "equal" child of the node
 * row-index + 2: if non-zero: index into the `nodes` array for the "greater" child of the node
 * The array has a fixed size of 254 rows (since we can address at most 256 - 1 values with the node MSB and
 * we always have fewer branching nodes than values).
 *
 * The `values` array contains the payload data addressed by trie leaf nodes.
 * Since we address them via the nodes MSB and zero is reserved the trie
 * cannot hold more then 255 items, so this array has a fixed size of 255.
 */
private[engine] final class HttpHeaderParser private (
  val settings: HttpHeaderParser.Settings,
  onIllegalHeader: ErrorInfo ⇒ Unit,
  private[this] var nodes: Array[Char] = new Array(512), // initial size, can grow as needed
  private[this] var nodeCount: Int = 0,
  private[this] var branchData: Array[Short] = new Array(254 * 3),
  private[this] var branchDataCount: Int = 0,
  private[this] var values: Array[AnyRef] = new Array(255), // fixed size of 255
  private[this] var valueCount: Int = 0,
  private[this] var trieIsPrivate: Boolean = false) { // signals the trie data can be mutated w/o having to copy first

  // TODO: evaluate whether switching to a value-class-based approach allows us to improve code readability without sacrificing performance

  import HttpHeaderParser._
  import settings._

  /**
   * Contains the parsed header instance after a call to `parseHeaderLine`.
   */
  var resultHeader: HttpHeader = EmptyHeader

  def isEmpty = nodeCount == 0

  /**
   * Returns a copy of this parser that shares the trie data with this instance.
   */
  def createShallowCopy(): HttpHeaderParser =
    new HttpHeaderParser(settings, onIllegalHeader, nodes, nodeCount, branchData, branchDataCount, values, valueCount)

  /**
   * Parses a header line and returns the line start index of the subsequent line.
   * The parsed header instance is written to the `resultHeader` member.
   * If the trie still has space for this type of header (and as a whole) the parsed header is cached.
   * Throws a `NotEnoughDataException` if the given input doesn't contain enough data to fully parse the given header
   * line. Note that there must be at least one byte available after a CRLF sequence, in order to distinguish a true
   * line ending from a line fold.
   * If the header is invalid a respective `ParsingException` is thrown.
   */
  @tailrec
  def parseHeaderLine(input: ByteString, lineStart: Int = 0)(cursor: Int = lineStart, nodeIx: Int = 0): Int = {
    def startValueBranch(rootValueIx: Int, valueParser: HeaderValueParser) = {
      val (header, endIx) = valueParser(this, input, cursor, onIllegalHeader)
      if (valueParser.cachingEnabled)
        try {
          val valueIx = newValueIndex // compute early in order to trigger OutOfTrieSpaceExceptions before any change
          unshareIfRequired()
          val nodeIx = nodeCount
          insertRemainingCharsAsNewNodes(input, header)(cursor, endIx, valueIx)
          values(rootValueIx) = ValueBranch(rootValueIx, valueParser, branchRootNodeIx = nodeIx, valueCount = 1)
        } catch {
          case OutOfTrieSpaceException ⇒ // if we cannot insert a value then we simply don't
        }
      resultHeader = header
      endIx
    }
    val node = nodes(nodeIx)
    node & 0xFF match {
      case 0 ⇒ // leaf node (or intermediate ValueBranch pointer)
        val valueIx = (node >>> 8) - 1
        values(valueIx) match {
          case branch: ValueBranch            ⇒ parseHeaderValue(input, cursor, branch)()
          case valueParser: HeaderValueParser ⇒ startValueBranch(valueIx, valueParser) // no header yet of this type
          case EmptyHeader                    ⇒ resultHeader = EmptyHeader; cursor
        }
      case nodeChar ⇒
        val char = CharUtils.toLowerCase(byteChar(input, cursor))
        if (char == node) // fast match, advance and descend
          parseHeaderLine(input, lineStart)(cursor + 1, nodeIx + 1)
        else node >>> 8 match {
          case 0 ⇒ // header doesn't exist yet and has no model (since we have not yet seen a colon)
            parseRawHeader(input, lineStart, cursor, nodeIx)
          case msb ⇒ // branching node
            val signum = math.signum(char - nodeChar)
            branchData(rowIx(msb) + 1 + signum) match {
              case 0 ⇒ // header doesn't exist yet and has no model (otherwise we'd arrive at a value)
                parseRawHeader(input, lineStart, cursor, nodeIx)
              case subNodeIx ⇒ // descend into branch and advance on char matches (otherwise descend but don't advance)
                parseHeaderLine(input, lineStart)(cursor + 1 - math.abs(signum), subNodeIx)
            }
        }
    }
  }

  private def parseRawHeader(input: ByteString, lineStart: Int, cursor: Int, nodeIx: Int): Int = {
    val colonIx = scanHeaderNameAndReturnIndexOfColon(input, lineStart, lineStart + 1 + maxHeaderNameLength)(cursor)
    val headerName = asciiString(input, lineStart, colonIx)
    try {
      val valueParser = new RawHeaderValueParser(headerName, maxHeaderValueLength, headerValueCacheLimit(headerName))
      insert(input, valueParser)(cursor, colonIx + 1, nodeIx, colonIx)
      parseHeaderLine(input, lineStart)(cursor, nodeIx)
    } catch {
      case OutOfTrieSpaceException ⇒ // if we cannot insert we drop back to simply creating new header instances
        val (headerValue, endIx) = scanHeaderValue(this, input, colonIx + 1, colonIx + maxHeaderValueLength + 3)()
        resultHeader = RawHeader(headerName, headerValue.trim)
        endIx
    }
  }

  @tailrec
  private def parseHeaderValue(input: ByteString, valueStart: Int, branch: ValueBranch)(cursor: Int = valueStart, nodeIx: Int = branch.branchRootNodeIx): Int = {
    def parseAndInsertHeader() = {
      val (header, endIx) = branch.parser(this, input, valueStart, onIllegalHeader)
      if (branch.spaceLeft)
        try {
          insert(input, header)(cursor, endIx, nodeIx, colonIx = 0)
          values(branch.valueIx) = branch.withValueCountIncreased
        } catch { case OutOfTrieSpaceException ⇒ /* if we cannot insert then we simply don't */ }
      resultHeader = header
      endIx
    }
    val char = byteChar(input, cursor)
    val node = nodes(nodeIx)
    if (char == node) // fast match, descend
      parseHeaderValue(input, valueStart, branch)(cursor + 1, nodeIx + 1)
    else node >>> 8 match {
      case 0 ⇒ parseAndInsertHeader()
      case msb ⇒ node & 0xFF match {
        case 0 ⇒ // leaf node
          resultHeader = values(msb - 1).asInstanceOf[HttpHeader]
          cursor
        case nodeChar ⇒ // branching node
          val signum = math.signum(char - nodeChar)
          branchData(rowIx(msb) + 1 + signum) match {
            case 0 ⇒ parseAndInsertHeader() // header doesn't exist yet
            case subNodeIx ⇒ // descend into branch and advance on char matches (otherwise descend but don't advance)
              parseHeaderValue(input, valueStart, branch)(cursor + 1 - math.abs(signum), subNodeIx)
          }
      }
    }
  }

  /**
   * Inserts a value into the cache trie.
   * CAUTION: this method must only be called if:
   *  - the trie is not empty (use `insertRemainingCharsAsNewNodes` for inserting the very first value)
   *  - the input does not contain illegal characters
   *  - the input is not a prefix of an already stored value, i.e. the input must be properly terminated (CRLF or colon)
   */
  @tailrec
  private def insert(input: ByteString, value: AnyRef)(cursor: Int = 0, endIx: Int = input.length, nodeIx: Int = 0, colonIx: Int = 0): Unit = {
    val char =
      if (cursor < colonIx) CharUtils.toLowerCase(input(cursor).toChar)
      else if (cursor < endIx) input(cursor).toChar
      else '\u0000'
    val node = nodes(nodeIx)
    if (char == node) insert(input, value)(cursor + 1, endIx, nodeIx + 1, colonIx) // fast match, descend into only subnode
    else {
      val nodeChar = node & 0xFF
      val signum = math.signum(char - nodeChar)
      node >>> 8 match {
        case 0 ⇒ // input doesn't exist yet in the trie, insert
          val valueIx = newValueIndex // compute early in order to trigger OutOfTrieSpaceExceptions before any change
          val rowIx = newBranchDataRowIndex
          unshareIfRequired()
          val newNodeIx = nodeCount.toShort
          insertRemainingCharsAsNewNodes(input, value)(cursor, endIx, valueIx, colonIx)
          nodes(nodeIx) = nodeBits(rowIx, nodeChar)
          branchData(rowIx + 1) = (nodeIx + 1).toShort
          branchData(rowIx + 1 + signum) = newNodeIx
        case msb ⇒
          if (nodeChar == 0) { // leaf node
            require(cursor == endIx, "Cannot insert key of which a prefix already has a value")
            values(msb - 1) = value // override existing entry
          } else {
            val branchIndex = rowIx(msb) + 1 + signum
            branchData(branchIndex) match { // branching node
              case 0 ⇒ // branch doesn't exist yet, create
                val valueIx = newValueIndex // compute early in order to trigger OutOfTrieSpaceExceptions before any change
                unshareIfRequired()
                val newNodeIx = nodeCount.toShort
                insertRemainingCharsAsNewNodes(input, value)(cursor, endIx, valueIx, colonIx)
                branchData(branchIndex) = newNodeIx // make the previously implicit "equals" sub node explicit
              case subNodeIx ⇒ // descend, but advance only on match
                insert(input, value)(cursor + 1 - math.abs(signum), endIx, subNodeIx, colonIx)
            }
          }
      }
    }
  }

  /**
   * Inserts a value into the cache trie as new nodes.
   * CAUTION: this method must only be called if the trie data have already been "unshared"!
   */
  @tailrec
  private def insertRemainingCharsAsNewNodes(input: ByteString, value: AnyRef)(cursor: Int = 0, endIx: Int = input.length, valueIx: Int = newValueIndex, colonIx: Int = 0): Unit = {
    val newNodeIx = newNodeIndex
    if (cursor < endIx) {
      val c = input(cursor).toChar
      val char = if (cursor < colonIx) CharUtils.toLowerCase(c) else c
      nodes(newNodeIx) = char
      insertRemainingCharsAsNewNodes(input, value)(cursor + 1, endIx, valueIx, colonIx)
    } else {
      values(valueIx) = value
      nodes(newNodeIx) = ((valueIx + 1) << 8).toChar
    }
  }

  private def unshareIfRequired(): Unit =
    if (!trieIsPrivate) {
      nodes = copyOf(nodes, nodes.length)
      branchData = copyOf(branchData, branchData.length)
      values = copyOf(values, values.length)
      trieIsPrivate = true
    }

  private def newNodeIndex: Int = {
    val index = nodeCount
    if (index < Short.MaxValue) {
      if (index == nodes.length) nodes = copyOf(nodes, math.min(index * 3 / 2, Short.MaxValue))
      nodeCount = index + 1
      index
    } else throw OutOfTrieSpaceException
  }

  private def newBranchDataRowIndex: Int = {
    val index = branchDataCount
    branchDataCount = index + 3
    index
  }

  private def newValueIndex: Int = {
    val index = valueCount
    if (index < values.length) {
      valueCount = index + 1
      index
    } else throw OutOfTrieSpaceException
  }

  private def rowIx(msb: Int) = (msb - 1) * 3
  private def nodeBits(rowIx: Int, char: Int) = (((rowIx / 3 + 1) << 8) | char).toChar

  /**
   * Renders the trie structure into an ASCII representation.
   */
  def formatTrie: String = {
    def recurse(nodeIx: Int = 0): (Seq[List[String]], Int) = {
      def recurseAndPrefixLines(subNodeIx: Int, p1: String, p2: String, p3: String) = {
        val (lines, mainIx) = recurse(subNodeIx)
        val prefixedLines = lines.zipWithIndex map {
          case (line, ix) ⇒ (if (ix < mainIx) p1 else if (ix > mainIx) p3 else p2) :: line
        }
        prefixedLines -> mainIx
      }
      def branchLines(dataIx: Int, p1: String, p2: String, p3: String) = branchData(dataIx) match {
        case 0         ⇒ Seq.empty
        case subNodeIx ⇒ recurseAndPrefixLines(subNodeIx, p1, p2, p3)._1
      }
      val node = nodes(nodeIx)
      val char = escape((node & 0xFF).toChar)
      node >>> 8 match {
        case 0 ⇒ recurseAndPrefixLines(nodeIx + 1, "  ", char + "-", "  ")
        case msb ⇒ node & 0xFF match {
          case 0 ⇒ values(msb - 1) match {
            case ValueBranch(_, valueParser, branchRootNodeIx, _) ⇒
              val pad = " " * (valueParser.headerName.length + 3)
              recurseAndPrefixLines(branchRootNodeIx, pad, "(" + valueParser.headerName + ")-", pad)
            case vp: HeaderValueParser ⇒ Seq(" (" :: vp.headerName :: ")" :: Nil) -> 0
            case value: RawHeader      ⇒ Seq(" *" :: value.toString :: Nil) -> 0
            case value                 ⇒ Seq(" " :: value.toString :: Nil) -> 0
          }
          case nodeChar ⇒
            val rix = rowIx(msb)
            val preLines = branchLines(rix, "  ", "┌─", "| ")
            val postLines = branchLines(rix + 2, "| ", "└─", "  ")
            val p1 = if (preLines.nonEmpty) "| " else "  "
            val p3 = if (postLines.nonEmpty) "| " else "  "
            val (matchLines, mainLineIx) = recurseAndPrefixLines(branchData(rix + 1), p1, char + '-', p3)
            (preLines ++ matchLines ++ postLines, mainLineIx + preLines.size)
        }
      }
    }
    val sb = new JStringBuilder()
    val (lines, mainLineIx) = recurse()
    lines.zipWithIndex foreach {
      case (line, ix) ⇒
        sb.append(if (ix == mainLineIx) '-' else ' ')
        line foreach (s ⇒ sb.append(s))
        sb.append('\n')
    }
    sb.toString
  }

  /**
   * Returns the number of header values stored per header type.
   */
  def contentHistogram: Map[String, Int] = {
    def build(nodeIx: Int = 0): Map[String, Int] = {
      val node = nodes(nodeIx)
      node >>> 8 match {
        case 0 ⇒ build(nodeIx + 1)
        case msb if (node & 0xFF) == 0 ⇒ values(msb - 1) match {
          case ValueBranch(_, parser, _, count) ⇒ Map(parser.headerName -> count)
          case _                                ⇒ Map.empty
        }
        case msb ⇒
          def branch(ix: Int): Map[String, Int] = if (ix > 0) build(ix) else Map.empty
          val rix = rowIx(msb)
          branch(branchData(rix + 0)) ++ branch(branchData(rix + 1)) ++ branch(branchData(rix + 2))
      }
    }
    build()
  }

  /**
   * Returns a string representation of the raw trie data.
   */
  def formatRawTrie: String = {
    def char(c: Char) = (c >> 8).toString + (if ((c & 0xFF) > 0) "/" + (c & 0xFF).toChar else "/Ω")
    s"nodes: ${nodes take nodeCount map char mkString ", "}\n" +
      s"branchData: ${branchData take branchDataCount grouped 3 map { case Array(a, b, c) ⇒ s"$a/$b/$c" } mkString ", "}\n" +
      s"values: ${values take valueCount mkString ", "}"
  }

  /**
   * Returns a string representation of the trie structure size.
   */
  def formatSizes: String = s"$nodeCount nodes, ${branchDataCount / 3} branchData rows, $valueCount values"

  // helpers for UTF-8 decoding,
  // since they are only accessed when an UTF8 byte sequence is actually hit and UTF-8 sequences in header values are
  // rare these fields can be lazy, the overhead of the lazy access should be overcompensated for by the saved
  // allocations in the majority of cases
  private lazy val byteBuffer = ByteBuffer.allocate(4)
  private lazy val charBuffer = CharBuffer.allocate(2)
  private lazy val decoder = UTF8.newDecoder()

  // returns the decoded character as a simple 16-bit Char value or a 32-bit surrogate pair
  // or -1 if the byteBuffer bytes are not a complete and legal UTF-8 byte sequence
  private def decodeByteBuffer(): Int = {
    byteBuffer.flip()
    val coderResult = decoder.decode(byteBuffer, charBuffer, false)
    charBuffer.flip()
    val result =
      if (coderResult.isUnderflow & charBuffer.hasRemaining) {
        val c = charBuffer.get()
        if (charBuffer.hasRemaining) (charBuffer.get() << 16) | c else c
      } else -1
    byteBuffer.clear()
    charBuffer.clear()
    result
  }
}

/**
 * INTERNAL API
 */
private[http] object HttpHeaderParser {
  import SpecializedHeaderValueParsers._

  abstract class Settings extends HeaderParser.Settings {
    def maxHeaderNameLength: Int
    def maxHeaderValueLength: Int
    def headerValueCacheLimit(headerName: String): Int
  }

  private def predefinedHeaders = Seq(
    "Accept: *",
    "Accept: */*",
    "Connection: Keep-Alive",
    "Connection: close",
    "Connection: keep-alive",
    "Content-Length: 0",
    "Cache-Control: max-age=0",
    "Cache-Control: no-cache",
    "Expect: 100-continue")

  def apply(settings: HttpHeaderParser.Settings)(onIllegalHeader: ErrorInfo ⇒ Unit = info ⇒ throw IllegalHeaderException(info)) =
    prime(unprimed(settings, onIllegalHeader))

  def unprimed(settings: HttpHeaderParser.Settings, warnOnIllegalHeader: ErrorInfo ⇒ Unit) =
    new HttpHeaderParser(settings, warnOnIllegalHeader)

  def prime(parser: HttpHeaderParser): HttpHeaderParser = {
    val valueParsers: Seq[HeaderValueParser] =
      HeaderParser.ruleNames.map { name ⇒
        new ModeledHeaderValueParser(name, parser.settings.maxHeaderValueLength, parser.settings.headerValueCacheLimit(name), parser.settings)
      }(collection.breakOut)
    def insertInGoodOrder(items: Seq[Any])(startIx: Int = 0, endIx: Int = items.size): Unit =
      if (endIx - startIx > 0) {
        val pivot = (startIx + endIx) / 2
        items(pivot) match {
          case valueParser: HeaderValueParser ⇒
            val insertName = valueParser.headerName.toRootLowerCase + ':'
            if (parser.isEmpty) parser.insertRemainingCharsAsNewNodes(ByteString(insertName), valueParser)()
            else parser.insert(ByteString(insertName), valueParser)()
          case header: String ⇒
            parser.parseHeaderLine(ByteString(header + "\r\nx"))()
        }
        insertInGoodOrder(items)(startIx, pivot)
        insertInGoodOrder(items)(pivot + 1, endIx)
      }
    insertInGoodOrder(valueParsers.sortBy(_.headerName))()
    insertInGoodOrder(specializedHeaderValueParsers)()
    insertInGoodOrder(predefinedHeaders.sorted)()
    parser.insert(ByteString("\r\n"), EmptyHeader)()
    parser
  }

  // helper forwarders for testing
  def insert(parser: HttpHeaderParser, input: ByteString, value: AnyRef): Unit =
    parser.insert(input, value)()
  def insertRemainingCharsAsNewNodes(parser: HttpHeaderParser, input: ByteString, value: AnyRef): Unit =
    parser.insertRemainingCharsAsNewNodes(input, value)()

  private[parsing] abstract class HeaderValueParser(val headerName: String, val maxValueCount: Int) {
    def apply(hhp: HttpHeaderParser, input: ByteString, valueStart: Int, onIllegalHeader: ErrorInfo ⇒ Unit): (HttpHeader, Int)
    override def toString: String = s"HeaderValueParser[$headerName]"
    def cachingEnabled = maxValueCount > 0
  }

  private[parsing] class ModeledHeaderValueParser(headerName: String, maxHeaderValueLength: Int, maxValueCount: Int, settings: HeaderParser.Settings)
    extends HeaderValueParser(headerName, maxValueCount) {
    def apply(hhp: HttpHeaderParser, input: ByteString, valueStart: Int, onIllegalHeader: ErrorInfo ⇒ Unit): (HttpHeader, Int) = {
      // TODO: optimize by running the header value parser directly on the input ByteString (rather than an extracted String)
      val (headerValue, endIx) = scanHeaderValue(hhp, input, valueStart, valueStart + maxHeaderValueLength + 2)()
      val trimmedHeaderValue = headerValue.trim
      val header = HeaderParser.parseFull(headerName, trimmedHeaderValue, settings) match {
        case Right(h) ⇒ h
        case Left(error) ⇒
          onIllegalHeader(error.withSummaryPrepended(s"Illegal '$headerName' header"))
          RawHeader(headerName, trimmedHeaderValue)
      }
      header -> endIx
    }
  }

  private[parsing] class RawHeaderValueParser(headerName: String, maxHeaderValueLength: Int, maxValueCount: Int)
    extends HeaderValueParser(headerName, maxValueCount) {
    def apply(hhp: HttpHeaderParser, input: ByteString, valueStart: Int, onIllegalHeader: ErrorInfo ⇒ Unit): (HttpHeader, Int) = {
      val (headerValue, endIx) = scanHeaderValue(hhp, input, valueStart, valueStart + maxHeaderValueLength + 2)()
      RawHeader(headerName, headerValue.trim) -> endIx
    }
  }

  @tailrec private def scanHeaderNameAndReturnIndexOfColon(input: ByteString, start: Int, limit: Int)(ix: Int): Int =
    if (ix < limit)
      byteChar(input, ix) match {
        case ':'           ⇒ ix
        case c if tchar(c) ⇒ scanHeaderNameAndReturnIndexOfColon(input, start, limit)(ix + 1)
        case c             ⇒ fail(s"Illegal character '${escape(c)}' in header name")
      }
    else fail(s"HTTP header name exceeds the configured limit of ${limit - start - 1} characters")

  @tailrec private def scanHeaderValue(hhp: HttpHeaderParser, input: ByteString, start: Int,
                                       limit: Int)(sb: JStringBuilder = null, ix: Int = start): (String, Int) = {
    def appended(c: Char) = (if (sb != null) sb else new JStringBuilder(asciiString(input, start, ix))).append(c)
    def appended2(c: Int) = if ((c >> 16) != 0) appended(c.toChar).append((c >> 16).toChar) else appended(c.toChar)
    if (ix < limit)
      byteChar(input, ix) match {
        case '\t' ⇒ scanHeaderValue(hhp, input, start, limit)(appended(' '), ix + 1)
        case '\r' if byteChar(input, ix + 1) == '\n' ⇒
          if (WSP(byteChar(input, ix + 2))) scanHeaderValue(hhp, input, start, limit)(appended(' '), ix + 3)
          else (if (sb != null) sb.toString else asciiString(input, start, ix), ix + 2)
        case c ⇒
          var nix = ix + 1
          val nsb =
            if (' ' <= c && c <= '\u007F') if (sb != null) sb.append(c) else null // legal 7-Bit ASCII
            else if ((c & 0xE0) == 0xC0) { // 2-byte UTF-8 sequence?
              hhp.byteBuffer.put(c.toByte)
              hhp.byteBuffer.put(byteAt(input, ix + 1))
              nix = ix + 2
              hhp.decodeByteBuffer() match { // if we cannot decode as UTF8 we don't decode but simply copy
                case -1 ⇒ if (sb != null) sb.append(c).append(byteChar(input, ix + 1)) else null
                case cc ⇒ appended2(cc)
              }
            } else if ((c & 0xF0) == 0xE0) { // 3-byte UTF-8 sequence?
              hhp.byteBuffer.put(c.toByte)
              hhp.byteBuffer.put(byteAt(input, ix + 1))
              hhp.byteBuffer.put(byteAt(input, ix + 2))
              nix = ix + 3
              hhp.decodeByteBuffer() match { // if we cannot decode as UTF8 we don't decode but simply copy
                case -1 ⇒ if (sb != null) sb.append(c).append(byteChar(input, ix + 1)).append(byteChar(input, ix + 2)) else null
                case cc ⇒ appended2(cc)
              }
            } else if ((c & 0xF8) == 0xF0) { // 4-byte UTF-8 sequence?
              hhp.byteBuffer.put(c.toByte)
              hhp.byteBuffer.put(byteAt(input, ix + 1))
              hhp.byteBuffer.put(byteAt(input, ix + 2))
              hhp.byteBuffer.put(byteAt(input, ix + 3))
              nix = ix + 4
              hhp.decodeByteBuffer() match { // if we cannot decode as UTF8 we don't decode but simply copy
                case -1 ⇒ if (sb != null) sb.append(c).append(byteChar(input, ix + 1)).append(byteChar(input, ix + 2)).append(byteChar(input, ix + 3)) else null
                case cc ⇒ appended2(cc)
              }
            } else fail(s"Illegal character '${escape(c)}' in header value")
          scanHeaderValue(hhp, input, start, limit)(nsb, nix)
      }
    else fail(s"HTTP header value exceeds the configured limit of ${limit - start - 2} characters")
  }

  def fail(summary: String) = throw new ParsingException(StatusCodes.BadRequest, ErrorInfo(summary))

  private object OutOfTrieSpaceException extends SingletonException

  /**
   * Instances of this class are added as "intermediate" values into the trie at the point where the header name has
   * been parsed (so we know the header type).
   * These instances behave like regular values (i.e. they live in the `values` array) but encapsulate meta information
   * about the trie header-type-specific branch below them.
   *
   * @param valueIx The index into the `values` array that contains this instance (kind of a "self" pointer)
   * @param parser The parser instance used to create the actual header model class for headers of this type
   * @param branchRootNodeIx the nodeIx for the root node of the trie branch holding all cached header values of this type
   * @param valueCount the number of values already stored in this header-type-specific branch
   */
  private final case class ValueBranch(valueIx: Int, parser: HeaderValueParser, branchRootNodeIx: Int, valueCount: Int) {
    def withValueCountIncreased = copy(valueCount = valueCount + 1)
    def spaceLeft = valueCount < parser.maxValueCount
  }
}