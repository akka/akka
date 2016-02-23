/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import akka.http.scaladsl.settings.ParserSettings
import com.typesafe.config.{ ConfigFactory, Config }
import scala.annotation.tailrec
import scala.util.Random
import org.scalatest.{ BeforeAndAfterAll, WordSpec, Matchers }
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers._
import akka.http.impl.model.parser.CharacterClasses
import akka.http.impl.util._

class HttpHeaderParserSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.http.parsing.max-header-name-length = 60
    akka.http.parsing.max-header-value-length = 1000
    akka.http.parsing.header-cache.Host = 300""")
  val system = ActorSystem(getClass.getSimpleName, testConf)

  "The HttpHeaderParser" should {
    "insert the 1st value" in new TestSetup(primed = false) {
      insert("Hello", 'Hello)
      check {
        """nodes: 0/H, 0/e, 0/l, 0/l, 0/o, 1/Ω
          |branchData:\u0020
          |values: 'Hello""" -> parser.formatRawTrie
      }
      check {
        """-H-e-l-l-o- 'Hello
          |""" -> parser.formatTrie
      }
    }

    "insert a new branch underneath a simple node" in new TestSetup(primed = false) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      check {
        """nodes: 0/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω
          |branchData: 6/2/0
          |values: 'Hello, 'Hallo""" -> parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          |""" -> parser.formatTrie
      }
    }

    "insert a new branch underneath the root" in new TestSetup(primed = false) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      insert("Yeah", 'Yeah)
      check {
        """nodes: 2/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω, 0/Y, 0/e, 0/a, 0/h, 3/Ω
          |branchData: 6/2/0, 0/1/11
          |values: 'Hello, 'Hallo, 'Yeah""" -> parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          | └─Y-e-a-h- 'Yeah
          |""" -> parser.formatTrie
      }
    }

    "insert a new branch underneath an existing branch node" in new TestSetup(primed = false) {
      insert("Hello", 'Hello)
      insert("Hallo", 'Hallo)
      insert("Yeah", 'Yeah)
      insert("Hoo", 'Hoo)
      check {
        """nodes: 2/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω, 0/Y, 0/e, 0/a, 0/h, 3/Ω, 0/o, 0/o, 4/Ω
          |branchData: 6/2/16, 0/1/11
          |values: 'Hello, 'Hallo, 'Yeah, 'Hoo""" -> parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- 'Hallo
          |-H-e-l-l-o- 'Hello
          | | └─o-o- 'Hoo
          | └─Y-e-a-h- 'Yeah
          |""" -> parser.formatTrie
      }
    }

    "support overriding of previously inserted values" in new TestSetup(primed = false) {
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
          |""" -> parser.formatTrie
      }
    }

    "retrieve the EmptyHeader" in new TestSetup() {
      parseAndCache("\r\n")() shouldEqual EmptyHeader
    }

    "retrieve a cached header with an exact header name match" in new TestSetup() {
      parseAndCache("Connection: close\r\nx")() shouldEqual Connection("close")
    }

    "retrieve a cached header with a case-insensitive header-name match" in new TestSetup() {
      parseAndCache("Connection: close\r\nx")("coNNection: close\r\nx") shouldEqual Connection("close")
    }

    "parse and cache a modelled header" in new TestSetup() {
      parseAndCache("Host: spray.io:123\r\nx")("HOST: spray.io:123\r\nx") shouldEqual Host("spray.io", 123)
    }

    "parse and cache an invalid modelled header as RawHeader" in new TestSetup() {
      parseAndCache("Content-Type: abc:123\r\nx")() shouldEqual RawHeader("content-type", "abc:123")
      parseAndCache("Origin: localhost:8080\r\nx")() shouldEqual RawHeader("origin", "localhost:8080")
    }

    "parse and cache an X-Forwarded-For with a hostname in it as a RawHeader" in new TestSetup() {
      parseAndCache("X-Forwarded-For: 1.2.3.4, akka.io\r\nx")() shouldEqual RawHeader("x-forwarded-for", "1.2.3.4, akka.io")
    }

    "parse and cache an X-Real-Ip with a hostname as it's value as a RawHeader" in new TestSetup() {
      parseAndCache("X-Real-Ip: akka.io\r\nx")() shouldEqual RawHeader("x-real-ip", "akka.io")
    }
    "parse and cache a raw header" in new TestSetup(primed = false) {
      insert("hello: bob", 'Hello)
      val (ixA, headerA) = parseLine("Fancy-Pants: foo\r\nx")
      val (ixB, headerB) = parseLine("Fancy-pants: foo\r\nx")
      check {
        """ ┌─f-a-n-c-y---p-a-n-t-s-:-(Fancy-Pants)- -f-o-o-\r-\n- *Fancy-Pants: foo
          |-h-e-l-l-o-:- -b-o-b- 'Hello
          |""" -> parser.formatTrie
      }
      ixA shouldEqual ixB
      headerA shouldEqual RawHeader("Fancy-Pants", "foo")
      headerA should be theSameInstanceAs headerB
    }

    "parse and cache a modelled header with line-folding" in new TestSetup() {
      parseAndCache("Connection: foo,\r\n bar\r\nx")("Connection: foo,\r\n bar\r\nx") shouldEqual Connection("foo", "bar")
    }

    "parse and cache a header with a tab char in the value" in new TestSetup() {
      parseAndCache("Fancy: foo\tbar\r\nx")() shouldEqual RawHeader("Fancy", "foo bar")
    }

    "parse and cache a header with UTF8 chars in the value" in new TestSetup() {
      parseAndCache("2-UTF8-Bytes: árvíztűrő ütvefúrógép\r\nx")() shouldEqual RawHeader("2-UTF8-Bytes", "árvíztűrő ütvefúrógép")
      parseAndCache("3-UTF8-Bytes: The € or the $?\r\nx")() shouldEqual RawHeader("3-UTF8-Bytes", "The € or the $?")
      parseAndCache("4-UTF8-Bytes: Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!\r\nx")() shouldEqual
        RawHeader("4-UTF8-Bytes", "Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!")
    }

    "produce an error message for lines with an illegal header name" in new TestSetup() {
      the[ParsingException] thrownBy parseLine(" Connection: close\r\nx") should have message "Illegal character ' ' in header name"
      the[ParsingException] thrownBy parseLine("Connection : close\r\nx") should have message "Illegal character ' ' in header name"
      the[ParsingException] thrownBy parseLine("Connec/tion: close\r\nx") should have message "Illegal character '/' in header name"
    }

    "produce an error message for lines with a too-long header name" in new TestSetup() {
      noException should be thrownBy parseLine("123456789012345678901234567890123456789012345678901234567890: foo\r\nx")
      the[ParsingException] thrownBy parseLine("1234567890123456789012345678901234567890123456789012345678901: foo\r\nx") should have message
        "HTTP header name exceeds the configured limit of 60 characters"
    }

    "produce an error message for lines with a too-long header value" in new TestSetup() {
      noException should be thrownBy parseLine(s"foo: ${nextRandomString(nextRandomAlphaNumChar, 1000)}\r\nx")
      the[ParsingException] thrownBy parseLine(s"foo: ${nextRandomString(nextRandomAlphaNumChar, 1001)}\r\nx") should have message
        "HTTP header value exceeds the configured limit of 1000 characters"
    }

    "continue parsing raw headers even if the overall cache value capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        val name = nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 16))
        val value = nextRandomString(nextRandomPrintableChar, nextRandomInt(4, 16))
        RawHeader(name, value)
      }
      randomHeaders.take(300).foldLeft(0) {
        case (acc, rawHeader) ⇒ acc + parseAndCache(rawHeader.toString + "\r\nx", rawHeader)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing modelled headers even if the overall cache value capacity is reached" in new TestSetup() {
      val randomHostHeaders = Stream.continually {
        Host(
          host = nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 8)),
          port = nextRandomInt(1000, 10000))
      }
      randomHostHeaders.take(300).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + "\r\nx", header)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing headers even if the overall cache node capacity is reached" in new TestSetup() {
      val randomHostHeaders = Stream.continually {
        RawHeader(
          name = nextRandomString(nextRandomAlphaNumChar, 60),
          value = nextRandomString(nextRandomAlphaNumChar, 1000))
      }
      randomHostHeaders.take(100).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + "\r\nx", header)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing raw headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        val value = nextRandomString(nextRandomPrintableChar, nextRandomInt(4, 16))
        RawHeader("Fancy", value)
      }
      randomHeaders.take(20).foldLeft(0) {
        case (acc, rawHeader) ⇒ acc + parseAndCache(rawHeader.toString + "\r\nx", rawHeader)
      } shouldEqual 12 // configured default per-header cache limit
    }

    "continue parsing modelled headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        `User-Agent`(nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 16)))
      }
      randomHeaders.take(40).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + "\r\nx", header)
      } shouldEqual 12 // configured default per-header cache limit
    }
  }

  override def afterAll() = system.terminate()

  def check(pair: (String, String)) = {
    val (expected, actual) = pair
    actual shouldEqual expected.stripMarginWithNewline("\n")
  }

  abstract class TestSetup(primed: Boolean = true) {
    val parser = {
      val p = HttpHeaderParser.unprimed(
        settings = ParserSettings(system),
        warnOnIllegalHeader = info ⇒ system.log.warning(info.formatPretty))
      if (primed) HttpHeaderParser.prime(p) else p
    }
    def insert(line: String, value: AnyRef): Unit =
      if (parser.isEmpty) HttpHeaderParser.insertRemainingCharsAsNewNodes(parser, ByteString(line), value)
      else HttpHeaderParser.insert(parser, ByteString(line), value)

    def parseLine(line: String) = parser.parseHeaderLine(ByteString(line))() -> parser.resultHeader

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
