/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import java.lang.{ StringBuilder ⇒ JStringBuilder }

import akka.http.scaladsl.settings.ParserSettings
import com.typesafe.config.{ Config, ConfigFactory }

import scala.annotation.tailrec
import scala.util.Random
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ ErrorInfo, HttpHeader, IllegalHeaderException }
import akka.http.scaladsl.model.headers._
import akka.http.impl.model.parser.CharacterClasses
import akka.http.impl.util._
import akka.http.scaladsl.settings.ParserSettings.IllegalResponseHeaderValueProcessingMode
import akka.testkit.TestKit

abstract class HttpHeaderParserSpec(mode: String, newLine: String) extends WordSpec with Matchers with BeforeAndAfterAll {

  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.http.parsing.max-header-name-length = 60
    akka.http.parsing.max-header-value-length = 1000
    akka.http.parsing.header-cache.Host = 300""")
  val system = ActorSystem(getClass.getSimpleName, testConf)

  s"The HttpHeaderParser (mode: $mode)" should {
    "insert the 1st value" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", 'Hello)
      check {
        """nodes: 0/H, 0/e, 0/l, 0/l, 0/o, 1/Ω
          |branchData:\u0020
          |values: 'Hello""" → parser.formatRawTrie
      }
      check {
        """-H-e-l-l-o- 'Hello
          |""" → parser.formatTrie
      }
    }

    "insert a new branch underneath a simple node" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      check {
        """nodes: 0/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω
          |branchData: 6/2/0
          |values: 'Hello, 'Hallo""" → parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          |""" → parser.formatTrie
      }
    }

    "insert a new branch underneath the root" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      insert("Yeah", 'Yeah)
      check {
        """nodes: 2/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω, 0/Y, 0/e, 0/a, 0/h, 3/Ω
          |branchData: 6/2/0, 0/1/11
          |values: 'Hello, 'Hallo, 'Yeah""" → parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          | └─Y-e-a-h- 'Yeah
          |""" → parser.formatTrie
      }
    }

    "insert a new branch underneath an existing branch node" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      insert("Yeah", 'Yeah)
      insert("Hoo", 'Hoo)
      check {
        """nodes: 2/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω, 0/Y, 0/e, 0/a, 0/h, 3/Ω, 0/o, 0/o, 4/Ω
          |branchData: 6/2/16, 0/1/11
          |values: 'Hello, 'Hallo, 'Yeah, 'Hoo""" → parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          | | └─o-o- 'Hoo
          | └─Y-e-a-h- 'Yeah
          |""" → parser.formatTrie
      }
    }

    "support overriding of previously inserted values" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      insert("Yeah", 'Yeah)
      insert("Hoo", 'Hoo)
      insert("Hoo", 'Foo)
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          | | └─o-o- 'Foo
          | └─Y-e-a-h- 'Yeah
          |""" → parser.formatTrie
      }
    }

    "retrieve the EmptyHeader" in new TestSetup() {
      parseAndCache(newLine)() shouldEqual EmptyHeader
    }

    "retrieve a cached header with an exact header name match" in new TestSetup() {
      parseAndCache(s"Connection: close${newLine}x")() shouldEqual Connection("close")
    }

    "retrieve a cached header with a case-insensitive header-name match" in new TestSetup() {
      parseAndCache(s"Connection: close${newLine}x")(s"coNNection: close${newLine}x") shouldEqual Connection("close")
    }

    "parse and cache a modelled header" in new TestSetup() {
      parseAndCache(s"Host: spray.io:123${newLine}x")(s"HOST: spray.io:123${newLine}x") shouldEqual Host("spray.io", 123)
    }

    "parse and cache an invalid modelled header as RawHeader" in new TestSetup() {
      parseAndCache(s"Content-Type: abc:123${newLine}x")() shouldEqual RawHeader("content-type", "abc:123")
      parseAndCache(s"Origin: localhost:8080${newLine}x")() shouldEqual RawHeader("origin", "localhost:8080")
    }

    "parse and cache an X-Forwarded-For with a hostname in it as a RawHeader" in new TestSetup() {
      parseAndCache(s"X-Forwarded-For: 1.2.3.4, akka.io${newLine}x")() shouldEqual RawHeader("x-forwarded-for", "1.2.3.4, akka.io")
    }

    "parse and cache an X-Real-Ip with a hostname as it's value as a RawHeader" in new TestSetup() {
      parseAndCache(s"X-Real-Ip: akka.io${newLine}x")() shouldEqual RawHeader("x-real-ip", "akka.io")
    }
    "parse and cache a raw header" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("hello: bob", 'Hello)
      val (ixA, headerA) = parseLine(s"Fancy-Pants: foo${newLine}x")
      val (ixB, headerB) = parseLine(s"Fancy-pants: foo${newLine}x")
      val newLineWithHyphen = if (newLine == "\r\n") """\r-\n""" else """\n"""
      check {
        s""" ┌─f-a-n-c-y---p-a-n-t-s-:-(Fancy-Pants)- -f-o-o-${newLineWithHyphen}- *Fancy-Pants: foo
           |-h-e-l-l-o-:- -b-o-b- 'Hello
           |""" → parser.formatTrie
      }
      ixA shouldEqual ixB
      headerA shouldEqual RawHeader("Fancy-Pants", "foo")
      headerA should be theSameInstanceAs headerB
    }

    "parse and cache a modelled header with line-folding" in new TestSetup() {
      parseAndCache(s"Connection: foo,${newLine} bar${newLine}x")(s"Connection: foo,${newLine} bar${newLine}x") shouldEqual Connection("foo", "bar")
    }

    "parse and cache a header with a tab char in the value" in new TestSetup() {
      parseAndCache(s"Fancy: foo\tbar${newLine}x")() shouldEqual RawHeader("Fancy", "foo bar")
    }

    "parse and cache a header with UTF8 chars in the value" in new TestSetup() {
      parseAndCache(s"2-UTF8-Bytes: árvíztűrő ütvefúrógép${newLine}x")() shouldEqual RawHeader("2-UTF8-Bytes", "árvíztűrő ütvefúrógép")
      parseAndCache(s"3-UTF8-Bytes: The € or the $$?${newLine}x")() shouldEqual RawHeader("3-UTF8-Bytes", "The € or the $?")
      parseAndCache(s"4-UTF8-Bytes: Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!${newLine}x")() shouldEqual
        RawHeader("4-UTF8-Bytes", "Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!")
    }

    "produce an error message for lines with an illegal header name" in new TestSetup() {
      the[ParsingException] thrownBy parseLine(s" Connection: close${newLine}x") should have message "Illegal character ' ' in header name"
      the[ParsingException] thrownBy parseLine(s"Connection : close${newLine}x") should have message "Illegal character ' ' in header name"
      the[ParsingException] thrownBy parseLine(s"Connec/tion: close${newLine}x") should have message "Illegal character '/' in header name"
    }

    "produce an error message for lines with a too-long header name" in new TestSetup() {
      noException should be thrownBy parseLine(s"123456789012345678901234567890123456789012345678901234567890: foo${newLine}x")
      the[ParsingException] thrownBy parseLine(s"1234567890123456789012345678901234567890123456789012345678901: foo${newLine}x") should have message
        "HTTP header name exceeds the configured limit of 60 characters"
    }

    "produce an error message for lines with a too-long header value" in new TestSetup() {
      noException should be thrownBy parseLine(s"foo: ${nextRandomString(nextRandomAlphaNumChar, 1000)}${newLine}x")
      the[ParsingException] thrownBy parseLine(s"foo: ${nextRandomString(nextRandomAlphaNumChar, 1001)}${newLine}x") should have message
        "HTTP header value exceeds the configured limit of 1000 characters"
    }

    "continue parsing raw headers even if the overall cache value capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        val name = nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 16))
        val value = nextRandomString(nextRandomPrintableChar, nextRandomInt(4, 16))
        RawHeader(name, value)
      }
      randomHeaders.take(300).foldLeft(0) {
        case (acc, rawHeader) ⇒ acc + parseAndCache(rawHeader.toString + s"${newLine}x", rawHeader)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing modelled headers even if the overall cache value capacity is reached" in new TestSetup() {
      val randomHostHeaders = Stream.continually {
        Host(
          host = nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 8)),
          port = nextRandomInt(1000, 10000))
      }
      randomHostHeaders.take(300).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + s"${newLine}x", header)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing headers even if the overall cache node capacity is reached" in new TestSetup() {
      val randomHostHeaders = Stream.continually {
        RawHeader(
          name = nextRandomString(nextRandomAlphaNumChar, 60),
          value = nextRandomString(nextRandomAlphaNumChar, 1000))
      }
      randomHostHeaders.take(100).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + s"${newLine}x", header)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing raw headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        val value = nextRandomString(nextRandomPrintableChar, nextRandomInt(4, 16))
        RawHeader("Fancy", value)
      }
      randomHeaders.take(20).foldLeft(0) {
        case (acc, rawHeader) ⇒ acc + parseAndCache(rawHeader.toString + s"${newLine}x", rawHeader)
      } shouldEqual 12 // configured default per-header cache limit
    }

    "continue parsing modelled headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        `User-Agent`(nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 16)))
      }
      randomHeaders.take(40).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + s"${newLine}x", header)
      } shouldEqual 12 // configured default per-header cache limit
    }

    "ignore headers whose value cannot be parsed" in new TestSetup(testSetupMode = TestSetupMode.Default) {
      noException should be thrownBy parseLine(s"Server: something; something${newLine}x")
      parseAndCache(s"Server: something; something${newLine}x")() shouldEqual RawHeader("server", "something; something")
    }
  }

  override def afterAll() = TestKit.shutdownActorSystem(system)

  def check(pair: (String, String)) = {
    val (expected, actual) = pair
    actual shouldEqual expected.stripMarginWithNewline("\n")
  }

  sealed trait TestSetupMode
  object TestSetupMode {
    case object Primed extends TestSetupMode
    case object Unprimed extends TestSetupMode
    case object Default extends TestSetupMode // creates a test setup using the default HttpHeaderParser.apply()
  }

  def createParserSettings(
    actorSystem:                              ActorSystem,
    illegalResponseHeaderValueProcessingMode: IllegalResponseHeaderValueProcessingMode = IllegalResponseHeaderValueProcessingMode.Error): ParserSettings =
    ParserSettings(actorSystem)
      .withIllegalResponseHeaderValueProcessingMode(illegalResponseHeaderValueProcessingMode)

  abstract class TestSetup(testSetupMode: TestSetupMode = TestSetupMode.Primed, parserSettings: ParserSettings = createParserSettings(system)) {

    val parser = testSetupMode match {
      case TestSetupMode.Primed   ⇒ HttpHeaderParser.prime(HttpHeaderParser.unprimed(parserSettings, system.log, defaultIllegalHeaderHandler))
      case TestSetupMode.Unprimed ⇒ HttpHeaderParser.unprimed(parserSettings, system.log, defaultIllegalHeaderHandler)
      case TestSetupMode.Default  ⇒ HttpHeaderParser(parserSettings, system.log)
    }

    private def defaultIllegalHeaderHandler = (info: ErrorInfo) ⇒ system.log.warning(info.formatPretty)

    def insert(line: String, value: AnyRef): Unit =
      if (parser.isEmpty) HttpHeaderParser.insertRemainingCharsAsNewNodes(parser, ByteString(line), value)
      else HttpHeaderParser.insert(parser, ByteString(line), value)

    def parseLine(line: String) = parser.parseHeaderLine(ByteString(line))() → { system.log.warning(parser.resultHeader.getClass.getSimpleName); parser.resultHeader }

    def parseAndCache(lineA: String)(lineB: String = lineA): HttpHeader = {
      val (ixA, headerA) = parseLine(lineA)
      val (ixB, headerB) = parseLine(lineB)
      ixA shouldEqual ixB
      headerA should be theSameInstanceAs headerB
      headerA
    }

    def parseAndCache(line: String, header: HttpHeader): Int = {
      val (ixA, headerA) = parseLine(line)
      val (ixB, headerB) = parseLine(line)
      headerA shouldEqual header
      headerB shouldEqual header
      ixA shouldEqual ixB
      if (headerA eq headerB) 1 else 0
    }

    private[this] val random = new Random(42)
    def nextRandomPrintableChar(): Char = random.nextPrintableChar()
    def nextRandomInt(min: Int, max: Int) = random.nextInt(max - min) + min
    @tailrec final def nextRandomAlphaNumChar(): Char = {
      val c = nextRandomPrintableChar()
      if (CharacterClasses.ALPHANUM(c)) c else nextRandomAlphaNumChar()
    }
    @tailrec final def nextRandomString(charGen: () ⇒ Char, len: Int, sb: JStringBuilder = new JStringBuilder): String =
      if (sb.length < len) nextRandomString(charGen, len, sb.append(charGen())) else sb.toString
  }
}

class HttpHeaderParserCRLFSpec extends HttpHeaderParserSpec("CRLF", "\r\n")

class HttpHeaderParserLFSpec extends HttpHeaderParserSpec("LF", "\n")