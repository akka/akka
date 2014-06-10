/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import com.typesafe.config.{ ConfigFactory, Config }
import scala.annotation.tailrec
import scala.util.Random
import org.scalatest.{ BeforeAndAfterAll, WordSpec, Matchers }
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.http.model.parser.CharacterClasses
import akka.http.model.HttpHeader
import akka.http.model.headers._
import akka.http.util._

class HttpHeaderParserSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.http.parsing.max-header-name-length = 20
    akka.http.parsing.max-header-value-length = 21
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

    "prime an empty parser with all defined HeaderValueParsers" in new TestSetup() {
      check {
        """   ┌─\r-\n- EmptyHeader
          |   |               ┌─c-h-a-r-s-e-t-:- (Accept-Charset)
          |   |       ┌─p-t---e-n-c-o-d-i-n-g-:- (Accept-Encoding)
          |   |       |     | | ┌─l-a-n-g-u-a-g-e-:- (Accept-Language)
          |   |       |     | └─r-a-n-g-e-s-:- (Accept-Ranges)
          |   |       |     |                ┌─\r-\n- Accept: */*
          |   |       |     └─:-(Accept)- -*-/-*-\r-\n- Accept: */*
          |   |       |                     ┌─a-l-l-o-w---c-r-e-d-e-n-t-i-a-l-s-:- (Access-Control-Allow-Credentials)
          |   |       |                     | |           |   ┌─h-e-a-d-e-r-s-:- (Access-Control-Allow-Headers)
          |   |       |                     | |           | ┌─m-e-t-h-o-d-s-:- (Access-Control-Allow-Methods)
          |   |       |                     | |           └─o-r-i-g-i-n-:- (Access-Control-Allow-Origin)
          |   |       |                     | | ┌─e-x-p-o-s-e---h-e-a-d-e-r-s-:- (Access-Control-Expose-Headers)
          |   |       |                     | └─m-a-x---a-g-e-:- (Access-Control-Max-Age)
          | ┌─a-c-c-e-s-s---c-o-n-t-r-o-l---r-e-q-u-e-s-t---h-e-a-d-e-r-s-:- (Access-Control-Request-Headers)
          | | | |                                           └─m-e-t-h-o-d-:- (Access-Control-Request-Method)
          | | | | ┌─l-l-o-w-:- (Allow)
          | | | └─u-t-h-o-r-i-z-a-t-i-o-n-:- (Authorization)
          | | |   ┌─a-c-h-e---c-o-n-t-r-o-l-:-(Cache-Control)- -m-a-x---a-g-e-=-0-\r-\n- Cache-Control: max-age=0
          | | |   |                                             └─n-o---c-a-c-h-e-\r-\n- Cache-Control: no-cache
          | | |   |   ┌─n-e-c-t-i-o-n-:-(Connection)- -K-e-e-p---A-l-i-v-e-\r-\n- Connection: Keep-Alive
          | | |   |   |                                | ┌─c-l-o-s-e-\r-\n- Connection: close
          | | |   |   |                                └─k-e-e-p---a-l-i-v-e-\r-\n- Connection: keep-alive
          | | └─c-o-n-t-e-n-t---d-i-s-p-o-s-i-t-i-o-n-:- (Content-Disposition)
          | |       |           |   ┌─e-n-c-o-d-i-n-g-:- (Content-Encoding)
          | |       |           | ┌─l-e-n-g-t-h-:-(Content-Length)- -0-\r-\n- Content-Length: 0
          | |       |           └─r-a-n-g-e-:- (Content-Range)
          | |       |             └─t-y-p-e-:- (Content-Type)
          | |       └─o-k-i-e-:- (Cookie)
          |-d-a-t-e-:- (Date)
          | |         ┌─t-a-g-:- (ETag)
          | |     ┌─e-x-p-e-c-t-:-(Expect)- -1-0-0---c-o-n-t-i-n-u-e-\r-\n- Expect: 100-continue
          | |   ┌─h-o-s-t-:- (Host)
          | |   |         ┌─a-t-c-h-:- (If-Match)
          | |   |     ┌─m-o-d-i-f-i-e-d---s-i-n-c-e-:- (If-Modified-Since)
          | | ┌─i-f---n-o-n-e---m-a-t-c-h-:- (If-None-Match)
          | | | |     | ┌─r-a-n-g-e-:- (If-Range)
          | | | |     └─u-n-m-o-d-i-f-i-e-d---s-i-n-c-e-:- (If-Unmodified-Since)
          | | | └─l-a-s-t---m-o-d-i-f-i-e-d-:- (Last-Modified)
          | | |     | ┌─i-n-k-:- (Link)
          | | |     └─o-c-a-t-i-o-n-:- (Location)
          | └─o-r-i-g-i-n-:- (Origin)
          |   |                         ┌─e-n-t-i-c-a-t-e-:- (Proxy-Authenticate)
          |   |   ┌─p-r-o-x-y---a-u-t-h-o-r-i-z-a-t-i-o-n-:- (Proxy-Authorization)
          |   | ┌─r-a-n-g-e-:- (Range)
          |   | |   └─e-m-o-t-e---a-d-d-r-e-s-s-:- (Remote-Address)
          |   | |   ┌─r-v-e-r-:- (Server)
          |   └─s-e-t---c-o-o-k-i-e-:- (Set-Cookie)
          |     |   ┌─t-r-a-n-s-f-e-r---e-n-c-o-d-i-n-g-:- (Transfer-Encoding)
          |     | ┌─u-s-e-r---a-g-e-n-t-:- (User-Agent)
          |     └─w-w-w---a-u-t-h-e-n-t-i-c-a-t-e-:- (WWW-Authenticate)
          |       └─x---f-o-r-w-a-r-d-e-d---f-o-r-:- (X-Forwarded-For)
          |""" -> parser.formatTrie
      }
      parser.formatSizes === "607 nodes, 41 branchData rows, 56 values"
      parser.contentHistogram ===
        Map("Connection" -> 3, "Content-Length" -> 1, "Accept" -> 2, "Cache-Control" -> 2, "Expect" -> 1)
    }

    "retrieve the EmptyHeader" in new TestSetup() {
      parseAndCache("\r\n")() === HttpHeaderParser.EmptyHeader
    }

    "retrieve a cached header with an exact header name match" in new TestSetup() {
      parseAndCache("Connection: close\r\nx")() === Connection("close")
    }

    "retrieve a cached header with a case-insensitive header-name match" in new TestSetup() {
      parseAndCache("Connection: close\r\nx")("coNNection: close\r\nx") === Connection("close")
    }

    "parse and cache a modelled header" in new TestSetup() {
      parseAndCache("Host: spray.io:123\r\nx")("HOST: spray.io:123\r\nx") === Host("spray.io", 123)
    }

    "parse and cache an invalid modelled header as RawHeader" in new TestSetup() {
      parseAndCache("Content-Type: abc:123\r\nx")() === RawHeader("Content-Type", "abc:123")
      parseAndCache("Origin: localhost:8080\r\nx")() === RawHeader("Origin", "localhost:8080")
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
      ixA === ixB
      headerA === RawHeader("Fancy-Pants", "foo")
      headerA should be theSameInstanceAs headerB
    }

    "parse and cache a modelled header with line-folding" in new TestSetup() {
      parseAndCache("Connection: foo,\r\n bar\r\nx")("Connection: foo,\r\n bar\r\nx") === Connection("foo", "bar")
    }

    "parse and cache a header with a tab char in the value" in new TestSetup() {
      parseAndCache("Fancy: foo\tbar\r\nx")() === RawHeader("Fancy", "foo bar")
    }

    "produce an error message for lines with an illegal header name" in new TestSetup() {
      the[ParsingException] thrownBy parseLine(" Connection: close\r\nx") should have message "Illegal character ' ' in header name"
      the[ParsingException] thrownBy parseLine("Connection : close\r\nx") should have message "Illegal character ' ' in header name"
      the[ParsingException] thrownBy parseLine("Connec/tion: close\r\nx") should have message "Illegal character '/' in header name"
    }

    "produce an error message for lines with a too-long header name" in new TestSetup() {
      the[ParsingException] thrownBy parseLine("123456789012345678901: foo\r\nx") should have message
        "HTTP header name exceeds the configured limit of 20 characters"
    }

    "produce an error message for lines with a too-long header value" in new TestSetup() {
      the[ParsingException] thrownBy parseLine("foo: 1234567890123456789012\r\nx") should have message
        "HTTP header value exceeds the configured limit of 21 characters"
    }

    "continue parsing raw headers even if the overall cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        val name = nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 16))
        val value = nextRandomString(nextRandomPrintableChar, nextRandomInt(4, 16))
        RawHeader(name, value)
      }
      randomHeaders.take(300).foldLeft(0) {
        case (acc, rawHeader) ⇒ acc + parseAndCache(rawHeader.toString + "\r\nx", rawHeader)
      } === 99 // number of cached headers
      parser.formatSizes === "3040 nodes, 114 branchData rows, 255 values"
    }

    "continue parsing modelled headers even if the overall cache capacity is reached" in new TestSetup() {
      val randomHostHeaders = Stream.continually {
        Host(
          host = nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 8)),
          port = nextRandomInt(1000, 10000))
      }
      randomHostHeaders.take(300).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + "\r\nx", header)
      } === 199 // number of cached headers
      parser.formatSizes === "3173 nodes, 186 branchData rows, 255 values"
    }

    "continue parsing raw headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        val value = nextRandomString(nextRandomPrintableChar, nextRandomInt(4, 16))
        RawHeader("Fancy", value)
      }
      randomHeaders.take(20).foldLeft(0) {
        case (acc, rawHeader) ⇒ acc + parseAndCache(rawHeader.toString + "\r\nx", rawHeader)
      } === 12
    }

    "continue parsing modelled headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Stream.continually {
        `User-Agent`(nextRandomString(nextRandomAlphaNumChar, nextRandomInt(4, 16)))
      }
      randomHeaders.take(40).foldLeft(0) {
        case (acc, header) ⇒ acc + parseAndCache(header.toString + "\r\nx", header)
      } === 32
    }
  }

  override def afterAll() = system.shutdown()

  def check(pair: (String, String)) = {
    val (expected, actual) = pair
    actual === expected.stripMarginWithNewline("\n")
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
      ixA === ixB
      headerA should be theSameInstanceAs headerB
      headerA
    }

    def parseAndCache(line: String, header: HttpHeader): Int = {
      val (ixA, headerA) = parseLine(line)
      val (ixB, headerB) = parseLine(line)
      headerA === header
      headerB === header
      ixA === ixB
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
