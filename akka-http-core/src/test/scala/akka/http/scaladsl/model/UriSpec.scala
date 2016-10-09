/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.nio.charset.Charset
import java.net.InetAddress
import akka.http.impl.util.StringRendering
import org.scalatest.matchers.{ MatchResult, Matcher }
import org.scalatest.{ Matchers, WordSpec }
import akka.parboiled2.UTF8
import Uri._

class UriSpec extends WordSpec with Matchers {

  "Uri.Host instances" should {

    "correctly parse empty hosts" in {
      Host("") shouldEqual Host.Empty
    }

    "parse correctly from IPv4 literals" in {
      Host("192.0.2.16") shouldEqual IPv4Host("192.0.2.16")
      Host("255.0.0.0") shouldEqual IPv4Host("255.0.0.0")
      Host("0.0.0.0") shouldEqual IPv4Host("0.0.0.0")
      Host("1.0.0.0") shouldEqual IPv4Host("1.0.0.0")
      Host("2.0.0.0") shouldEqual IPv4Host("2.0.0.0")
      Host("3.0.0.0") shouldEqual IPv4Host("3.0.0.0")
      Host("30.0.0.0") shouldEqual IPv4Host("30.0.0.0")
    }

    "support inetAddresses round-trip for Inet4Addresses" in {
      def roundTrip(ip: String): Unit = {
        val inetAddr = InetAddress.getByName(ip)
        val addr = Host(inetAddr)
        addr shouldEqual IPv4Host(ip)
        addr.inetAddresses shouldEqual Seq(inetAddr)
      }

      roundTrip("192.0.2.16")
      roundTrip("192.0.2.16")
      roundTrip("255.0.0.0")
      roundTrip("0.0.0.0")
      roundTrip("1.0.0.0")
      roundTrip("2.0.0.0")
      roundTrip("3.0.0.0")
      roundTrip("30.0.0.0")
    }

    "parse correctly from IPv6 literals (RFC2732)" in {
      // various
      Host("[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]") shouldEqual IPv6Host("FEDCBA9876543210FEDCBA9876543210", "FEDC:BA98:7654:3210:FEDC:BA98:7654:3210")
      Host("[1080:0:0:0:8:800:200C:417A]") shouldEqual IPv6Host("108000000000000000080800200C417A", "1080:0:0:0:8:800:200C:417A")
      Host("[3ffe:2a00:100:7031::1]") shouldEqual IPv6Host("3ffe2a00010070310000000000000001", "3ffe:2a00:100:7031::1")
      Host("[1080::8:800:200C:417A]") shouldEqual IPv6Host("108000000000000000080800200C417A", "1080::8:800:200C:417A")
      Host("[::192.9.5.5]") shouldEqual IPv6Host("000000000000000000000000C0090505", "::192.9.5.5")
      Host("[::FFFF:129.144.52.38]") shouldEqual IPv6Host("00000000000000000000FFFF81903426", "::FFFF:129.144.52.38")
      Host("[2010:836B:4179::836B:4179]") shouldEqual IPv6Host("2010836B4179000000000000836B4179", "2010:836B:4179::836B:4179")

      // Quad length
      Host("[abcd::]") shouldEqual IPv6Host("ABCD0000000000000000000000000000", "abcd::")
      Host("[abcd::1]") shouldEqual IPv6Host("ABCD0000000000000000000000000001", "abcd::1")
      Host("[abcd::12]") shouldEqual IPv6Host("ABCD0000000000000000000000000012", "abcd::12")
      Host("[abcd::123]") shouldEqual IPv6Host("ABCD0000000000000000000000000123", "abcd::123")
      Host("[abcd::1234]") shouldEqual IPv6Host("ABCD0000000000000000000000001234", "abcd::1234")

      // Full length
      Host("[2001:0db8:0100:f101:0210:a4ff:fee3:9566]") shouldEqual IPv6Host("20010db80100f1010210a4fffee39566", "2001:0db8:0100:f101:0210:a4ff:fee3:9566") // lower hex
      Host("[2001:0DB8:0100:F101:0210:A4FF:FEE3:9566]") shouldEqual IPv6Host("20010db80100f1010210a4fffee39566", "2001:0DB8:0100:F101:0210:A4FF:FEE3:9566") // Upper hex
      Host("[2001:db8:100:f101:210:a4ff:fee3:9566]") shouldEqual IPv6Host("20010db80100f1010210a4fffee39566", "2001:db8:100:f101:210:a4ff:fee3:9566")
      Host("[2001:0db8:100:f101:0:0:0:1]") shouldEqual IPv6Host("20010db80100f1010000000000000001", "2001:0db8:100:f101:0:0:0:1")
      Host("[1:2:3:4:5:6:255.255.255.255]") shouldEqual IPv6Host("000100020003000400050006FFFFFFFF", "1:2:3:4:5:6:255.255.255.255")

      // Legal IPv4
      Host("[::1.2.3.4]") shouldEqual IPv6Host("00000000000000000000000001020304", "::1.2.3.4")
      Host("[3:4::5:1.2.3.4]") shouldEqual IPv6Host("00030004000000000000000501020304", "3:4::5:1.2.3.4")
      Host("[::ffff:1.2.3.4]") shouldEqual IPv6Host("00000000000000000000ffff01020304", "::ffff:1.2.3.4")
      Host("[::0.0.0.0]") shouldEqual IPv6Host("00000000000000000000000000000000", "::0.0.0.0") // Min IPv4
      Host("[::255.255.255.255]") shouldEqual IPv6Host("000000000000000000000000FFFFFFFF", "::255.255.255.255") // Max IPv4

      // Zipper position
      Host("[::1:2:3:4:5:6:7]") shouldEqual IPv6Host("00000001000200030004000500060007", "::1:2:3:4:5:6:7")
      Host("[1::1:2:3:4:5:6]") shouldEqual IPv6Host("00010000000100020003000400050006", "1::1:2:3:4:5:6")
      Host("[1:2::1:2:3:4:5]") shouldEqual IPv6Host("00010002000000010002000300040005", "1:2::1:2:3:4:5")
      Host("[1:2:3::1:2:3:4]") shouldEqual IPv6Host("00010002000300000001000200030004", "1:2:3::1:2:3:4")
      Host("[1:2:3:4::1:2:3]") shouldEqual IPv6Host("00010002000300040000000100020003", "1:2:3:4::1:2:3")
      Host("[1:2:3:4:5::1:2]") shouldEqual IPv6Host("00010002000300040005000000010002", "1:2:3:4:5::1:2")
      Host("[1:2:3:4:5:6::1]") shouldEqual IPv6Host("00010002000300040005000600000001", "1:2:3:4:5:6::1")
      Host("[1:2:3:4:5:6:7::]") shouldEqual IPv6Host("00010002000300040005000600070000", "1:2:3:4:5:6:7::")

      // Zipper length
      Host("[1:1:1::1:1:1:1]") shouldEqual IPv6Host("00010001000100000001000100010001", "1:1:1::1:1:1:1")
      Host("[1:1:1::1:1:1]") shouldEqual IPv6Host("00010001000100000000000100010001", "1:1:1::1:1:1")
      Host("[1:1:1::1:1]") shouldEqual IPv6Host("00010001000100000000000000010001", "1:1:1::1:1")
      Host("[1:1::1:1]") shouldEqual IPv6Host("00010001000000000000000000010001", "1:1::1:1")
      Host("[1:1::1]") shouldEqual IPv6Host("00010001000000000000000000000001", "1:1::1")
      Host("[1::1]") shouldEqual IPv6Host("00010000000000000000000000000001", "1::1")
      Host("[::1]") shouldEqual IPv6Host("00000000000000000000000000000001", "::1") // == localhost
      Host("[::]") shouldEqual IPv6Host("00000000000000000000000000000000", "::") // == all addresses

      // A few more variations
      Host("[21ff:abcd::1]") shouldEqual IPv6Host("21ffabcd000000000000000000000001", "21ff:abcd::1")
      Host("[2001:db8:100:f101::1]") shouldEqual IPv6Host("20010db80100f1010000000000000001", "2001:db8:100:f101::1")
      Host("[a:b:c::12:1]") shouldEqual IPv6Host("000a000b000c00000000000000120001", "a:b:c::12:1")
      Host("[a:b::0:1:2:3]") shouldEqual IPv6Host("000a000b000000000000000100020003", "a:b::0:1:2:3")
    }
    "support inetAddresses round-trip for Inet6Addresses" in {
      def fromAddress(address: String): IPv6Host = Host(s"[$address]").asInstanceOf[IPv6Host]
      def roundTrip(ip: String): Unit = {
        val inetAddr = InetAddress.getByName(ip)
        val addr = Host(inetAddr)
        addr equalsIgnoreCase fromAddress(ip) should be(true)
        addr.inetAddresses shouldEqual Seq(inetAddr)
      }

      roundTrip("1:1:1::1:1:1:1")
      roundTrip("::1:2:3:4:5:6:7")
      roundTrip("2001:0DB8:0100:F101:0210:A4FF:FEE3:9566")
      roundTrip("2001:0db8:100:f101:0:0:0:1")
      roundTrip("abcd::12")
      roundTrip("::192.9.5.5")
    }

    "parse correctly from NamedHost literals" in {
      Host("www.spray.io") shouldEqual NamedHost("www.spray.io")
      Host("localhost") shouldEqual NamedHost("localhost")
      Host("%2FH%C3%A4ll%C3%B6%5C") shouldEqual NamedHost("""/hällö\""")
    }

    "not accept illegal IPv4 literals" in {
      Host("01.0.0.0") shouldBe a[NamedHost]
      Host("001.0.0.0") shouldBe a[NamedHost]
      Host("00.0.0.0") shouldBe a[NamedHost]
      Host("000.0.0.0") shouldBe a[NamedHost]
      Host("256.0.0.0") shouldBe a[NamedHost]
      Host("300.0.0.0") shouldBe a[NamedHost]
      Host("1111.0.0.0") shouldBe a[NamedHost]
      Host("-1.0.0.0") shouldBe a[NamedHost]
      Host("0.0.0") shouldBe a[NamedHost]
      Host("0.0.0.") shouldBe a[NamedHost]
      Host("0.0.0.0.") shouldBe a[NamedHost]
      Host("0.0.0.0.0") shouldBe a[NamedHost]
      Host("0.0..0") shouldBe a[NamedHost]
      Host(".0.0.0") shouldBe a[NamedHost]
    }

    "not accept illegal IPv6 literals" in {
      // 5 char quad
      the[IllegalUriException] thrownBy Host("[::12345]") shouldBe {
        IllegalUriException(
          "Illegal URI host: Invalid input '5', expected ':' or ']' (line 1, column 8)",
          "[::12345]\n" +
            "       ^")
      }

      // Two zippers
      a[IllegalUriException] should be thrownBy Host("[abcd::abcd::abcd]")

      // Triple-colon zipper
      a[IllegalUriException] should be thrownBy Host("[:::1234]")
      a[IllegalUriException] should be thrownBy Host("[1234:::1234:1234]")
      a[IllegalUriException] should be thrownBy Host("[1234:1234:::1234]")
      a[IllegalUriException] should be thrownBy Host("[1234:::]")

      // No quads, just IPv4
      a[IllegalUriException] should be thrownBy Host("[1.2.3.4]")
      a[IllegalUriException] should be thrownBy Host("[0001.0002.0003.0004]")

      // Five quads
      a[IllegalUriException] should be thrownBy Host("[0000:0000:0000:0000:0000:1.2.3.4]")

      // Seven quads
      a[IllegalUriException] should be thrownBy Host("[0:0:0:0:0:0:0]")
      a[IllegalUriException] should be thrownBy Host("[0:0:0:0:0:0:0:]")
      a[IllegalUriException] should be thrownBy Host("[0:0:0:0:0:0:0:1.2.3.4]")

      // Nine quads
      a[IllegalUriException] should be thrownBy Host("[0:0:0:0:0:0:0:0:0]")

      // Invalid IPv4 part
      a[IllegalUriException] should be thrownBy Host("[::ffff:001.02.03.004]") // Leading zeros
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3.1111]") // Four char octet
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3.256]") // > 255
      a[IllegalUriException] should be thrownBy Host("[::ffff:311.2.3.4]") // > 155
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3:4]") // Not a dot
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3]") // Missing octet
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3.]") // Missing octet
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3a.4]") // Hex in octet
      a[IllegalUriException] should be thrownBy Host("[::ffff:1.2.3.4:123]") // Crap input

      // Nonhex
      a[IllegalUriException] should be thrownBy Host("[g:0:0:0:0:0:0]")
    }
  }

  "Uri.Path instances" should {
    import Path.Empty
    "be parsed and rendered correctly" in {
      def roundTripTo(p: Path, cs: Charset = UTF8) =
        Matcher[String] { s ⇒
          val rendering = UriRendering.renderPath(new StringRendering, p, cs).get
          if (rendering != s) MatchResult(matches = false, s"The path rendered to '$rendering' rather than '$s'", "<?>")
          else if (Path(s, cs) != p) MatchResult(matches = false, s"The string parsed to '${Path(s, cs)}' rather than '$p'", "<?>")
          else MatchResult(matches = true, "<?>", "<?>")
        }

      "" should roundTripTo(Empty)
      "/" should roundTripTo(Path./)
      "a" should roundTripTo("a" :: Empty)
      "//" should roundTripTo(Path./ / "")
      "a/" should roundTripTo("a" :: Path./)
      "/a" should roundTripTo(Path / "a")
      "/abc/de/f" should roundTripTo(Path / "abc" / "de" / "f")
      "abc/de/f/" should roundTripTo("abc" :: '/' :: "de" :: '/' :: "f" :: Path./)
      "abc///de" should roundTripTo("abc" :: '/' :: '/' :: '/' :: "de" :: Empty)
      "/abc%2F" should roundTripTo(Path / "abc/")
      "/:foo:/" should roundTripTo(Path / ":foo:" / "")
      "/%2520" should roundTripTo(Path / "%20")
      "/foo%20bar" should roundTripTo(Path / "foo bar")
      "H%C3%A4ll%C3%B6" should roundTripTo("Hällö" :: Empty)
      "/%2F%5C" should roundTripTo(Path / """/\""")
      "/foo%F0%9F%92%A9bar" should roundTripTo(Path / "foo\ud83d\udca9bar")
      "/%C3%89g%20get%20eti%C3%B0%20gler%20%C3%A1n%20%C3%BEess%20a%C3%B0%20mei%C3%B0a%20mig" should
        roundTripTo(Path / "Ég get etið gler án þess að meiða mig")
      "/%00%E4%00%F6%00%FC" should roundTripTo(Path / "äöü", Charset.forName("UTF-16BE"))
    }
    "support the `startsWith` predicate" in {
      Empty startsWith Empty shouldBe true
      Path./ startsWith Empty shouldBe true
      Path("abc") startsWith Empty shouldBe true
      Empty startsWith Path./ shouldBe false
      Empty startsWith Path("abc") shouldBe false
      Path./ startsWith Path./ shouldBe true
      Path./ startsWith Path("abc") shouldBe false
      Path("/abc") startsWith Path./ shouldBe true
      Path("abc") startsWith Path./ shouldBe false
      Path("abc") startsWith Path("ab") shouldBe true
      Path("abc") startsWith Path("abc") shouldBe true
      Path("/abc") startsWith Path("/a") shouldBe true
      Path("/abc") startsWith Path("/abc") shouldBe true
      Path("/ab") startsWith Path("/abc") shouldBe false
      Path("/abc") startsWith Path("/abd") shouldBe false
      Path("/abc/def") startsWith Path("/ab") shouldBe true
      Path("/abc/def") startsWith Path("/abc/") shouldBe true
      Path("/abc/def") startsWith Path("/abc/d") shouldBe true
      Path("/abc/def") startsWith Path("/abc/def") shouldBe true
      Path("/abc/def") startsWith Path("/abc/def/") shouldBe false
    }
    "support the `endsWithSlash` predicate" in {
      Empty.endsWithSlash shouldBe false
      Path./.endsWithSlash shouldBe true
      Path("abc").endsWithSlash shouldBe false
      Path("abc/").endsWithSlash shouldBe true
      Path("/abc").endsWithSlash shouldBe false
      Path("/abc/def").endsWithSlash shouldBe false
      Path("/abc/def/").endsWithSlash shouldBe true
    }
    "support the `dropChars` modifier" in {
      Path./.dropChars(0) shouldEqual Path./
      Path./.dropChars(1) shouldEqual Empty
      Path("/abc/def/").dropChars(0) shouldEqual Path("/abc/def/")
      Path("/abc/def/").dropChars(1) shouldEqual Path("abc/def/")
      Path("/abc/def/").dropChars(2) shouldEqual Path("bc/def/")
      Path("/abc/def/").dropChars(3) shouldEqual Path("c/def/")
      Path("/abc/def/").dropChars(4) shouldEqual Path("/def/")
      Path("/abc/def/").dropChars(5) shouldEqual Path("def/")
      Path("/abc/def/").dropChars(6) shouldEqual Path("ef/")
      Path("/abc/def/").dropChars(7) shouldEqual Path("f/")
      Path("/abc/def/").dropChars(8) shouldEqual Path("/")
      Path("/abc/def/").dropChars(9) shouldEqual Empty
    }
  }

  "Uri.Query instances" should {
    "be parsed correctly in strict mode" in {
      //#query-strict-definition
      def strict(queryString: String): Query = Query(queryString, mode = Uri.ParsingMode.Strict)
      //#query-strict-definition

      //#query-strict-mode
      //query component "a=b" is parsed into parameter name: "a", and value: "b"
      strict("a=b") shouldEqual ("a", "b") +: Query.Empty

      strict("") shouldEqual ("", "") +: Query.Empty
      strict("a") shouldEqual ("a", "") +: Query.Empty
      strict("a=") shouldEqual ("a", "") +: Query.Empty
      strict("a=+") shouldEqual ("a", " ") +: Query.Empty //'+' is parsed to ' '
      strict("a=%2B") shouldEqual ("a", "+") +: Query.Empty
      strict("=a") shouldEqual ("", "a") +: Query.Empty
      strict("a&") shouldEqual ("a", "") +: ("", "") +: Query.Empty
      strict("a=%62") shouldEqual ("a", "b") +: Query.Empty

      strict("a%3Db=c") shouldEqual ("a=b", "c") +: Query.Empty
      strict("a%26b=c") shouldEqual ("a&b", "c") +: Query.Empty
      strict("a%2Bb=c") shouldEqual ("a+b", "c") +: Query.Empty
      strict("a%3Bb=c") shouldEqual ("a;b", "c") +: Query.Empty

      strict("a=b%3Dc") shouldEqual ("a", "b=c") +: Query.Empty
      strict("a=b%26c") shouldEqual ("a", "b&c") +: Query.Empty
      strict("a=b%2Bc") shouldEqual ("a", "b+c") +: Query.Empty
      strict("a=b%3Bc") shouldEqual ("a", "b;c") +: Query.Empty

      strict("a+b=c") shouldEqual ("a b", "c") +: Query.Empty //'+' is parsed to ' '
      strict("a=b+c") shouldEqual ("a", "b c") +: Query.Empty //'+' is parsed to ' '
      //#query-strict-mode

      //#query-strict-without-percent-encode
      strict("a?b=c") shouldEqual ("a?b", "c") +: Query.Empty
      strict("a/b=c") shouldEqual ("a/b", "c") +: Query.Empty

      strict("a=b?c") shouldEqual ("a", "b?c") +: Query.Empty
      strict("a=b/c") shouldEqual ("a", "b/c") +: Query.Empty
      //#query-strict-without-percent-encode

      //#query-strict-mode-exception-1
      the[IllegalUriException] thrownBy strict("a^=b") shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input '^', expected '+', '=', query-char, 'EOI', '&' or pct-encoded (line 1, column 2)",
          "a^=b\n" +
            " ^")
      }
      the[IllegalUriException] thrownBy strict("a;=b") shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input ';', expected '+', '=', query-char, 'EOI', '&' or pct-encoded (line 1, column 2)",
          "a;=b\n" +
            " ^")
      }
      //#query-strict-mode-exception-1

      //#query-strict-mode-exception-2
      //double '=' in query string is invalid
      the[IllegalUriException] thrownBy strict("a=b=c") shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input '=', expected '+', query-char, 'EOI', '&' or pct-encoded (line 1, column 4)",
          "a=b=c\n" +
            "   ^")
      }
      //following '%', it should be percent encoding (HEXDIG), but "%b=" is not a valid percent encoding
      the[IllegalUriException] thrownBy strict("a%b=c") shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input '=', expected HEXDIG (line 1, column 4)",
          "a%b=c\n" +
            "   ^")
      }
      //#query-strict-mode-exception-2
    }
    "be parsed correctly in relaxed mode" in {
      //#query-relaxed-mode
      def relaxed(queryString: String): Query = Query(queryString, mode = Uri.ParsingMode.Relaxed)
      //#query-relaxed-mode

      relaxed("") shouldEqual ("", "") +: Query.Empty
      relaxed("a") shouldEqual ("a", "") +: Query.Empty
      relaxed("a=") shouldEqual ("a", "") +: Query.Empty
      relaxed("a=+") shouldEqual ("a", " ") +: Query.Empty
      relaxed("a=%2B") shouldEqual ("a", "+") +: Query.Empty
      relaxed("=a") shouldEqual ("", "a") +: Query.Empty
      relaxed("a&") shouldEqual ("a", "") +: ("", "") +: Query.Empty
      relaxed("a=%62") shouldEqual ("a", "b") +: Query.Empty

      relaxed("a%3Db=c") shouldEqual ("a=b", "c") +: Query.Empty
      relaxed("a%26b=c") shouldEqual ("a&b", "c") +: Query.Empty
      relaxed("a%2Bb=c") shouldEqual ("a+b", "c") +: Query.Empty
      relaxed("a%3Bb=c") shouldEqual ("a;b", "c") +: Query.Empty

      relaxed("a=b%3Dc") shouldEqual ("a", "b=c") +: Query.Empty
      relaxed("a=b%26c") shouldEqual ("a", "b&c") +: Query.Empty
      relaxed("a=b%2Bc") shouldEqual ("a", "b+c") +: Query.Empty
      relaxed("a=b%3Bc") shouldEqual ("a", "b;c") +: Query.Empty

      relaxed("a+b=c") shouldEqual ("a b", "c") +: Query.Empty //'+' is parsed to ' '
      relaxed("a=b+c") shouldEqual ("a", "b c") +: Query.Empty //'+' is parsed to ' '

      //without percent encoding
      relaxed("a?b=c") shouldEqual ("a?b", "c") +: Query.Empty
      relaxed("a/b=c") shouldEqual ("a/b", "c") +: Query.Empty

      relaxed("a=b?c") shouldEqual ("a", "b?c") +: Query.Empty
      relaxed("a=b/c") shouldEqual ("a", "b/c") +: Query.Empty

      //#query-relaxed-mode-success
      relaxed("a^=b") shouldEqual ("a^", "b") +: Query.Empty
      relaxed("a;=b") shouldEqual ("a;", "b") +: Query.Empty
      //#query-relaxed-mode-success

      //#query-relaxed-mode-exception
      //double '=' in query string is invalid, even in relaxed mode
      the[IllegalUriException] thrownBy relaxed("a=b=c") shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input '=', expected '+', query-char, 'EOI', '&' or pct-encoded (line 1, column 4)",
          "a=b=c\n" +
            "   ^")
      }
      //following '%', it should be percent encoding (HEXDIG), but "%b=" is not a valid percent encoding
      //still invalid even in relaxed mode
      the[IllegalUriException] thrownBy relaxed("a%b=c") shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input '=', expected HEXDIG (line 1, column 4)",
          "a%b=c\n" +
            "   ^")
      }
      //#query-relaxed-mode-exception
    }
    "properly support the retrieval interface" in {
      val query = Query("a=1&b=2&c=3&b=4&b")
      query.get("a") shouldEqual Some("1")
      query.get("d") shouldEqual None
      query.getOrElse("a", "x") shouldEqual "1"
      query.getOrElse("d", "x") shouldEqual "x"
      query.getAll("b") shouldEqual List("", "4", "2")
      query.getAll("d") shouldEqual Nil
      query.toMap shouldEqual Map("a" → "1", "b" → "", "c" → "3")
      query.toMultiMap shouldEqual Map("a" → List("1"), "b" → List("", "4", "2"), "c" → List("3"))
      query.toList shouldEqual List("a" → "1", "b" → "2", "c" → "3", "b" → "4", "b" → "")
      query.toSeq shouldEqual Seq("a" → "1", "b" → "2", "c" → "3", "b" → "4", "b" → "")
    }
    "support conversion from list of name/value pairs" in {
      import Query._
      val pairs = List("key1" → "value1", "key2" → "value2", "key3" → "value3")
      Query(pairs: _*).toList.diff(pairs) shouldEqual Nil
      Query() shouldEqual Empty
      Query("k" → "v") shouldEqual ("k" → "v") +: Empty
    }
    "encode special separators in query parameter names" in {
      Query("a=b" → "c").toString() shouldEqual "a%3Db=c"
      Query("a&b" → "c").toString() shouldEqual "a%26b=c"
      Query("a+b" → "c").toString() shouldEqual "a%2Bb=c"
      Query("a;b" → "c").toString() shouldEqual "a%3Bb=c"
    }
    "encode special separators in query parameter values" in {
      Query("a" → "b=c").toString() shouldEqual "a=b%3Dc"
      Query("a" → "b&c").toString() shouldEqual "a=b%26c"
      Query("a" → "b+c").toString() shouldEqual "a=b%2Bc"
      Query("a" → "b;c").toString() shouldEqual "a=b%3Bc"
    }
  }

  "URIs" should {

    // http://tools.ietf.org/html/rfc3986#section-1.1.2
    "be correctly parsed from and rendered to simple test examples" in {

      //#valid-uri-examples
      Uri("ftp://ftp.is.co.za/rfc/rfc1808.txt") shouldEqual
        Uri.from(scheme = "ftp", host = "ftp.is.co.za", path = "/rfc/rfc1808.txt")

      Uri("http://www.ietf.org/rfc/rfc2396.txt") shouldEqual
        Uri.from(scheme = "http", host = "www.ietf.org", path = "/rfc/rfc2396.txt")

      Uri("ldap://[2001:db8::7]/c=GB?objectClass?one") shouldEqual
        Uri.from(scheme = "ldap", host = "[2001:db8::7]", path = "/c=GB", queryString = Some("objectClass?one"))

      Uri("mailto:John.Doe@example.com") shouldEqual
        Uri.from(scheme = "mailto", path = "John.Doe@example.com")

      Uri("news:comp.infosystems.www.servers.unix") shouldEqual
        Uri.from(scheme = "news", path = "comp.infosystems.www.servers.unix")

      Uri("tel:+1-816-555-1212") shouldEqual
        Uri.from(scheme = "tel", path = "+1-816-555-1212")

      Uri("telnet://192.0.2.16:80/") shouldEqual
        Uri.from(scheme = "telnet", host = "192.0.2.16", port = 80, path = "/")

      Uri("urn:oasis:names:specification:docbook:dtd:xml:4.1.2") shouldEqual
        Uri.from(scheme = "urn", path = "oasis:names:specification:docbook:dtd:xml:4.1.2")
      //#valid-uri-examples

      // more examples
      Uri("http://") shouldEqual Uri(scheme = "http", authority = Authority(Host.Empty))
      Uri("http:?") shouldEqual Uri.from(scheme = "http", queryString = Some(""))
      Uri("http:") shouldEqual Uri.from(scheme = "http", queryString = None)
      Uri("?a+b=c%2Bd").query() shouldEqual ("a b", "c+d") +: Query.Empty

      // illegal paths
      Uri("foo/another@url/[]and{}") shouldEqual Uri.from(path = "foo/another@url/%5B%5Dand%7B%7D")
      a[IllegalUriException] should be thrownBy Uri("foo/another@url/[]and{}", mode = Uri.ParsingMode.Strict)

      // handle query parameters with more than percent-encoded character
      Uri("?%7Ba%7D=$%7B%7D", UTF8, Uri.ParsingMode.Strict).query() shouldEqual Query.Cons("{a}", s"$${}", Query.Empty)

      //#dont-double-decode
      // don't double decode
      Uri("%2520").path.head shouldEqual "%20"
      Uri("/%2F%5C").path shouldEqual Path / """/\"""
      //#dont-double-decode

      // render
      Uri("https://server.com/path/to/here?st=12345").toString shouldEqual "https://server.com/path/to/here?st=12345"
      Uri("/foo/?a#b").toString shouldEqual "/foo/?a#b"

      // empty host
      Uri("http://:8000/foo") shouldEqual Uri("http", Authority(Host.Empty, 8000), Path / "foo")
      Uri("http://:80/foo") shouldEqual Uri("http", Authority(Host.Empty, 80), Path / "foo")
    }

    "properly complete a normalization cycle" in {

      // http://tools.ietf.org/html/rfc3986#section-6.2.2
      normalize("eXAMPLE://a/./b/../b/%63/%7bfoo%7d") shouldEqual "example://a/b/c/%7Bfoo%7D"

      // more examples
      normalize("") shouldEqual ""
      normalize("/") shouldEqual "/"
      normalize("../../") shouldEqual "../../"
      normalize("aBc") shouldEqual "aBc"

      normalize("Http://Localhost") shouldEqual "http://localhost"
      normalize("hTtP://localHost") shouldEqual "http://localhost"
      normalize("https://:443") shouldEqual "https://"
      normalize("https://:444") shouldEqual "https://:444"
      normalize("http://:80/foo") shouldEqual "http:///foo"
      normalize("http://:8080/foo") shouldEqual "http://:8080/foo"
      normalize("ftp://example.com:21") shouldEqual "ftp://example.com"
      normalize("example.com:21") shouldEqual "example.com:21" // example.com is parsed as the SCHEME (which is correct)
      normalize("//example.com:21") shouldEqual "//example.com:21"
      normalize("ftp://example.com:22") shouldEqual "ftp://example.com:22"

      normalize("//user:pass@[::1]:80/segment/index.html?query#frag") shouldEqual "//user:pass@[::1]:80/segment/index.html?query#frag"
      normalize("http://[::1]:80/segment/index.html?query#frag") shouldEqual "http://[::1]/segment/index.html?query#frag"
      normalize("http://user:pass@[::1]/segment/index.html?query#frag") shouldEqual "http://user:pass@[::1]/segment/index.html?query#frag"
      normalize("http://user:pass@[::1]:80?query#frag") shouldEqual "http://user:pass@[::1]?query#frag"
      normalize("http://user:pass@[::1]/segment/index.html#frag") shouldEqual "http://user:pass@[::1]/segment/index.html#frag"
      normalize("http://user:pass@[::1]:81/segment/index.html?query") shouldEqual "http://user:pass@[::1]:81/segment/index.html?query"
      normalize("ftp://host:21/gnu/") shouldEqual "ftp://host/gnu/"
      normalize("one/two/three") shouldEqual "one/two/three"
      normalize("/one/two/three") shouldEqual "/one/two/three"
      normalize("//user:pass@localhost/one/two/three") shouldEqual "//user:pass@localhost/one/two/three"
      normalize("http://www.example.com/") shouldEqual "http://www.example.com/"
      normalize("http://sourceforge.net/projects/uriparser/") shouldEqual "http://sourceforge.net/projects/uriparser/"
      normalize("http://sourceforge.net/project/platformdownload.php?group_id=182840") shouldEqual "http://sourceforge.net/project/platformdownload.php?group_id=182840"
      normalize("mailto:test@example.com") shouldEqual "mailto:test@example.com"
      normalize("file:/bin/bash") shouldEqual "file:///bin/bash"
      normalize("http://www.example.com/name%20with%20spaces/") shouldEqual "http://www.example.com/name%20with%20spaces/"
      normalize("http://examp%4Ce.com/") shouldEqual "http://example.com/"
      normalize("http://example.com/a/b/%2E%2E/") shouldEqual "http://example.com/a/"
      normalize("http://user:pass@SOMEHOST.COM:123") shouldEqual "http://user:pass@somehost.com:123"
      normalize("HTTP://a:b@HOST:123/./1/2/../%41?abc#def") shouldEqual "http://a:b@host:123/1/A?abc#def"

      // acceptance and normalization of unescaped ascii characters such as {} and []:
      normalize("eXAMPLE://a/./b/../b/%63/{foo}/[bar]") shouldEqual "example://a/b/c/%7Bfoo%7D/%5Bbar%5D"
      a[IllegalUriException] should be thrownBy normalize("eXAMPLE://a/./b/../b/%63/{foo}/[bar]", mode = Uri.ParsingMode.Strict)

      // queries and fragments
      normalize("?") shouldEqual "?"
      normalize("?key") shouldEqual "?key"
      normalize("?key=") shouldEqual "?key="
      normalize("?key=&a=b") shouldEqual "?key=&a=b"
      normalize("?key={}&a=[]") shouldEqual "?key={}&a=[]"
      normalize("?=value") shouldEqual "?=value"
      normalize("?key=value") shouldEqual "?key=value"
      normalize("?a+b") shouldEqual "?a+b"
      normalize("?=a+b") shouldEqual "?=a+b"
      normalize("?a+b=c+d") shouldEqual "?a+b=c+d"
      normalize("??") shouldEqual "??"
      normalize("?a=1&b=2") shouldEqual "?a=1&b=2"
      normalize("?a+b=c%2Bd") shouldEqual "?a+b=c%2Bd"
      normalize("?a&a") shouldEqual "?a&a"
      normalize("?&#") shouldEqual "?&#"
      normalize("?#") shouldEqual "?#"
      normalize("#") shouldEqual "#"
      normalize("#{}[]") shouldEqual "#%7B%7D%5B%5D"
      a[IllegalUriException] should be thrownBy normalize("#{}[]", mode = Uri.ParsingMode.Strict)
    }

    "support tunneling a URI through a query param" in {
      val uri = Uri("http://aHost/aPath?aParam=aValue#aFragment")
      val q = Query("uri" → uri.toString)
      val uri2 = Uri(path = Path./, fragment = Some("aFragment")).withQuery(q).toString
      uri2 shouldEqual "/?uri=http://ahost/aPath?aParam%3DaValue%23aFragment#aFragment"
      Uri(uri2).query() shouldEqual q
      Uri(q.getOrElse("uri", "<nope>")) shouldEqual uri
    }

    "produce proper error messages for illegal URIs" in {
      //#illegal-cases-immediate-exception
      //illegal scheme
      the[IllegalUriException] thrownBy Uri("foö:/a") shouldBe {
        IllegalUriException(
          "Illegal URI reference: Invalid input 'ö', expected scheme-char, 'EOI', '#', ':', '?', slashSegments or pchar (line 1, column 3)",
          "foö:/a\n" +
            "  ^")
      }

      // illegal userinfo
      the[IllegalUriException] thrownBy Uri("http://user:ö@host") shouldBe {
        IllegalUriException(
          "Illegal URI reference: Invalid input 'ö', expected userinfo-char, pct-encoded, '@' or port (line 1, column 13)",
          "http://user:ö@host\n" +
            "            ^")
      }

      // illegal percent-encoding
      the[IllegalUriException] thrownBy Uri("http://use%2G@host") shouldBe {
        IllegalUriException(
          "Illegal URI reference: Invalid input 'G', expected HEXDIG (line 1, column 13)",
          "http://use%2G@host\n" +
            "            ^")
      }

      // illegal path
      the[IllegalUriException] thrownBy Uri("http://www.example.com/name with spaces/") shouldBe {
        IllegalUriException(
          "Illegal URI reference: Invalid input ' ', expected '/', 'EOI', '#', '?' or pchar (line 1, column 28)",
          "http://www.example.com/name with spaces/\n" +
            "                           ^")
      }

      // illegal path with control character
      the[IllegalUriException] thrownBy Uri("http:///with\newline") shouldBe {
        IllegalUriException(
          "Illegal URI reference: Invalid input '\\n', expected '/', 'EOI', '#', '?' or pchar (line 1, column 13)",
          "http:///with\n" +
            "            ^")
      }
      //#illegal-cases-immediate-exception

      // illegal query
      the[IllegalUriException] thrownBy Uri("?a=b=c").query() shouldBe {
        IllegalUriException(
          "Illegal query: Invalid input '=', expected '+', query-char, 'EOI', '&' or pct-encoded (line 1, column 4)",
          "a=b=c\n" +
            "   ^")
      }
    }

    // http://tools.ietf.org/html/rfc3986#section-5.4
    "pass the RFC 3986 reference resolution examples" when {
      val base = parseAbsolute("http://a/b/c/d;p?q")
      def resolve(uri: String) = parseAndResolve(uri, base).toString

      "normal examples" in {
        resolve("g:h") shouldEqual "g:h"
        resolve("g") shouldEqual "http://a/b/c/g"
        resolve("./g") shouldEqual "http://a/b/c/g"
        resolve("g/") shouldEqual "http://a/b/c/g/"
        resolve("/g") shouldEqual "http://a/g"
        resolve("//g") shouldEqual "http://g"
        resolve("?y") shouldEqual "http://a/b/c/d;p?y"
        resolve("g?y") shouldEqual "http://a/b/c/g?y"
        resolve("#s") shouldEqual "http://a/b/c/d;p?q#s"
        resolve("g#s") shouldEqual "http://a/b/c/g#s"
        resolve("g?y#s") shouldEqual "http://a/b/c/g?y#s"
        resolve(";x") shouldEqual "http://a/b/c/;x"
        resolve("g;x") shouldEqual "http://a/b/c/g;x"
        resolve("g;x?y#s") shouldEqual "http://a/b/c/g;x?y#s"
        resolve("") shouldEqual "http://a/b/c/d;p?q"
        resolve(".") shouldEqual "http://a/b/c/"
        resolve("./") shouldEqual "http://a/b/c/"
        resolve("..") shouldEqual "http://a/b/"
        resolve("../") shouldEqual "http://a/b/"
        resolve("../g") shouldEqual "http://a/b/g"
        resolve("../..") shouldEqual "http://a/"
        resolve("../../") shouldEqual "http://a/"
        resolve("../../g") shouldEqual "http://a/g"
      }

      "abnormal examples" in {
        resolve("../../../g") shouldEqual "http://a/g"
        resolve("../../../../g") shouldEqual "http://a/g"

        resolve("/./g") shouldEqual "http://a/g"
        resolve("/../g") shouldEqual "http://a/g"
        resolve("g.") shouldEqual "http://a/b/c/g."
        resolve(".g") shouldEqual "http://a/b/c/.g"
        resolve("g..") shouldEqual "http://a/b/c/g.."
        resolve("..g") shouldEqual "http://a/b/c/..g"

        resolve("./../g") shouldEqual "http://a/b/g"
        resolve("./g/.") shouldEqual "http://a/b/c/g/"
        resolve("g/./h") shouldEqual "http://a/b/c/g/h"
        resolve("g/../h") shouldEqual "http://a/b/c/h"
        resolve("g;x=1/./y") shouldEqual "http://a/b/c/g;x=1/y"
        resolve("g;x=1/../y") shouldEqual "http://a/b/c/y"

        resolve("g?y/./x") shouldEqual "http://a/b/c/g?y/./x"
        resolve("g?y/../x") shouldEqual "http://a/b/c/g?y/../x"
        resolve("g#s/./x") shouldEqual "http://a/b/c/g#s/./x"
        resolve("g#s/../x") shouldEqual "http://a/b/c/g#s/../x"

        resolve("http:g") shouldEqual "http:g"
      }
    }

    "be properly copyable" in {
      val uri = Uri("http://host:80/path?query#fragment")
      uri.copy() shouldEqual uri
    }

    "provide sugar for fluent transformations" in {
      val uri = Uri("http://host/path?query#fragment")
      val explicitDefault = Uri("http://host:80/path?query#fragment")
      val nonDefaultUri = Uri("http://host:6060/path?query#fragment")

      uri.withScheme("https") shouldEqual Uri("https://host/path?query#fragment")
      explicitDefault.withScheme("https") shouldEqual Uri("https://host:80/path?query#fragment")
      nonDefaultUri.withScheme("https") shouldEqual Uri("https://host:6060/path?query#fragment")

      uri.withAuthority(Authority(Host("other"), 3030)) shouldEqual Uri("http://other:3030/path?query#fragment")
      uri.withAuthority(Host("other"), 3030) shouldEqual Uri("http://other:3030/path?query#fragment")
      uri.withAuthority("other", 3030) shouldEqual Uri("http://other:3030/path?query#fragment")

      uri.withHost(Host("other")) shouldEqual Uri("http://other/path?query#fragment")
      explicitDefault.withHost(Host("other")) shouldEqual Uri("http://other:80/path?query#fragment")
      uri.withHost("other") shouldEqual Uri("http://other/path?query#fragment")
      explicitDefault.withHost("other") shouldEqual Uri("http://other:80/path?query#fragment")
      uri.withPort(90) shouldEqual Uri("http://host:90/path?query#fragment")
      explicitDefault.withPort(90) shouldEqual Uri("http://host:90/path?query#fragment")

      uri.withPath(Path("/newpath")) shouldEqual Uri("http://host/newpath?query#fragment")
      explicitDefault.withPath(Path("/newpath")) shouldEqual Uri("http://host:80/newpath?query#fragment")

      uri.withUserInfo("someInfo") shouldEqual Uri("http://someInfo@host/path?query#fragment")
      explicitDefault.withUserInfo("someInfo") shouldEqual Uri("http://someInfo@host:80/path?query#fragment")

      uri.withQuery(Query("param1" → "value1")) shouldEqual Uri("http://host/path?param1=value1#fragment")
      uri.withQuery(Query(Map("param1" → "value1"))) shouldEqual Uri("http://host/path?param1=value1#fragment")
      uri.withRawQueryString("param1=value1") shouldEqual Uri("http://host/path?param1=value1#fragment")

      uri.withFragment("otherFragment") shouldEqual Uri("http://host/path?query#otherFragment")
    }

    "return the correct effective port" in {
      Uri("http://host/").effectivePort shouldEqual 80
      Uri("ftp://host/").effectivePort shouldEqual 21
      Uri("http://host:9090/").effectivePort shouldEqual 9090
      Uri("https://host/").effectivePort shouldEqual 443

      Uri("https://host/").withPort(4450).effectivePort shouldEqual 4450
      Uri("https://host:3030/").withPort(4450).effectivePort shouldEqual 4450
    }

    "keep the specified authority port" in {
      Uri("example.com").withPort(0).authority.port shouldEqual 0
      Uri("example.com").withPort(80).authority.port shouldEqual 80
      Uri("http://example.com").withPort(80).authority.port shouldEqual 80
      Uri("http://example.com").withPort(0).authority.port shouldEqual 0
      Uri("https://example.com").withPort(0).authority.port shouldEqual 0
      Uri("https://example.com").withPort(443).authority.port shouldEqual 443
    }

    "properly render as HTTP request target origin forms" in {
      Uri("http://example.com/foo/bar?query=1#frag").toHttpRequestTargetOriginForm.toString === "/foo/bar?query=1"
      Uri("http://example.com//foo/bar?query=1#frag").toHttpRequestTargetOriginForm.toString === "//foo/bar?query=1"
    }

    "survive parsing a URI with thousands of path segments" in {
      val slashes = "/a/" * 2000
      val uri = Uri(s"http://foo.bar/$slashes")
      uri.toString // was reported to throw StackOverflowException in Spray's URI
    }

    "survive parsing a URI with thousands of query string values" in {
      val uriString = (1 to 2000).map("a=" + _).mkString("http://foo.bar/?", "&", "")
      val uri = Uri(uriString)
      val query = uri.query()
      query.size shouldEqual 2000
      query.head._2 shouldEqual "1"
      query.last._2 shouldEqual "2000"
    }
  }
}
