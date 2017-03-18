/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Accept, Cookie, Host }
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Attributes
import akka.stream.scaladsl.Source
import akka.testkit.AkkaSpec
import akka.util.ByteString
import org.scalatest.{ Inside, Inspectors }

class RequestParsingSpec extends AkkaSpec() with Inside with Inspectors {

  "RequestParsing" should {

    /** Helper to test parsing */
    def parse(
      keyValuePairs:  Seq[(String, String)],
      data:           Source[ByteString, Any] = Source.empty,
      attributes:     Attributes              = Attributes(),
      uriParsingMode: Uri.ParsingMode         = Uri.ParsingMode.Relaxed
    ): HttpRequest = {
      // Stream containing the request
      val subStream = Http2SubStream(
        initialHeaders = ParsedHeadersFrame(
          streamId = 1,
          endStream = true,
          keyValuePairs = keyValuePairs,
          priorityInfo = None
        ),
        data = data
      )
      // Create the parsing function
      val parseRequest: Http2SubStream ⇒ HttpRequest = {
        val (serverSettings, parserSettings) = {
          val ss = ServerSettings(system)
          val ps = ss.parserSettings.withUriParsingMode(uriParsingMode)
          (ss.withParserSettings(ps), ps)
        }
        val headerParser = HttpHeaderParser(parserSettings, log)
        RequestParsing.parseRequest(headerParser, serverSettings, attributes)
      }
      parseRequest(subStream)
    }

    def shouldThrowMalformedRequest[T](block: ⇒ T): Exception = {
      val thrown = the[RuntimeException] thrownBy block
      thrown.getMessage should startWith("Malformed request: ")
      thrown
    }

    "follow RFC7540" should {

      // 8.1.2.1.  Pseudo-Header Fields

      // ... pseudo-header fields defined for responses MUST NOT
      // appear in requests.

      "not accept response pseudo-header fields in a request" in {
        val thrown = shouldThrowMalformedRequest(parse(
          keyValuePairs = Vector(
            ":scheme" → "https",
            ":method" → "GET",
            ":path" → "/",
            ":status" → "200"
          )))
        thrown.getMessage should ===("Malformed request: Pseudo-header ':status' is for responses only; it cannot appear in a request")
      }

      // All pseudo-header fields MUST appear in the header block before
      // regular header fields.  Any request or response that contains a
      // pseudo-header field that appears in a header block after a regular
      // header field MUST be treated as malformed...

      "not accept pseudo-header fields after regular headers" in {
        val pseudoHeaders = Vector(
          ":method" → "GET",
          ":scheme" → "https",
          ":path" → "/"
        )
        forAll(0 until pseudoHeaders.length) { insertPoint: Int ⇒
          // Insert the Foo header so it occurs before at least one pseudo-header
          val (before, after) = pseudoHeaders.splitAt(insertPoint)
          val modified = before ++ Vector("Foo" → "bar") ++ after
          shouldThrowMalformedRequest(parse(modified))
        }
      }

      // 8.1.2.2.  Connection-Specific Header Fields

      // ...any message containing connection-specific header fields MUST
      // be treated as malformed...

      "not accept connection-specific headers" in pendingUntilFixed {
        shouldThrowMalformedRequest {
          // Add Connection header to indicate that Foo is a connection-specific header
          parse(Vector(
            ":method" → "GET",
            ":scheme" → "https",
            ":path" → "/",
            "Connection" → "foo",
            "Foo" → "bar"
          ))
        }
      }

      // 8.1.2.3.  Request Pseudo-Header Fields

      // The ":method" pseudo-header field includes the HTTP method
      // ...

      "parse the ':method' pseudo-header correctly" in {
        val methods = Seq("GET", "POST", "DELETE", "OPTIONS")
        forAll(methods) { method: String ⇒
          val request: HttpRequest = parse(
            keyValuePairs = Vector(
              ":method" → method,
              ":scheme" → "https",
              ":path" → "/"
            ))
          request.method.value should ===(method)
        }
      }

      // The ":scheme" pseudo-header field includes the scheme portion of
      // the target URI ([RFC3986], Section 3.1).
      //
      // ":scheme" is not restricted to "http" and "https" schemed URIs.  A
      // proxy or gateway can translate requests for non-HTTP schemes,
      // enabling the use of HTTP to interact with non-HTTP services.

      "parse the ':scheme' pseudo-header correctly" in {
        // ws/wss are not supported in HTTP/2, but they're useful for this test.
        // We're restricted in what we can test because the HttpRequest class
        // can't be constructed with any other schemes.
        val schemes = Seq("http", "https", "ws", "wss")
        forAll(schemes) { scheme: String ⇒
          val request: HttpRequest = parse(
            keyValuePairs = Vector(
              ":method" → "POST",
              ":scheme" → scheme,
              ":path" → "/"
            ))
          request.uri.scheme should ===(scheme)
        }
      }

      // The ":authority" pseudo-header field includes the authority
      // portion of the target URI ([RFC3986], Section 3.2).

      "follow RFC3986 for the ':path' pseudo-header" should {

        "parse a valid ':authority' (without userinfo)" in {
          // Examples from RFC3986
          val authorities = Seq(
            ("", "", None),
            ("ftp.is.co.za", "ftp.is.co.za", None),
            ("www.ietf.org", "www.ietf.org", None),
            ("[2001:db8::7]", "2001:db8::7", None),
            ("192.0.2.16:80", "192.0.2.16", Some(80)),
            ("example.com:8042", "example.com", Some(8042))
          )
          forAll(authorities) {
            case (authority, host, optPort) ⇒
              val request: HttpRequest = parse(
                keyValuePairs = Vector(
                  ":method" → "POST",
                  ":scheme" → "https",
                  ":authority" → authority,
                  ":path" → "/"
                ))
              request.uri.authority.host.address should ===(host)
              request.uri.authority.port should ===(optPort.getOrElse(0))
          }
        }

        "reject an invalid ':authority'" in {

          val authorities = Seq("?", " ", "@", ":")
          forAll(authorities) { authority ⇒
            val thrown = the[ParsingException] thrownBy (parse(
              keyValuePairs = Vector(
                ":method" → "POST",
                ":scheme" → "https",
                ":authority" → authority,
                ":path" → "/"
              )))
            thrown.getMessage should include("http2-authority-pseudo-header")
          }
        }
      }

      // ... The authority
      // MUST NOT include the deprecated "userinfo" subcomponent for "http"
      // or "https" schemed URIs.

      // [Can't test any schemes that would allow userinfo, since restrictions
      // on HttpRequest objects mean we can only test http, https, ws and wss schemes,
      // none of which should probably allow userinfo in the authority.]
      "not accept a 'userinfo' value in the :authority pseudo-header for http and https" in {
        // Examples from RFC3986
        val authorities = Seq(
          "@localhost",
          "John.Doe@example.com",
          "cnn.example.com&story=breaking_news@10.0.0.1"
        )
        val schemes = Seq("http", "https")
        forAll(schemes) { scheme: String ⇒
          forAll(authorities) { authority: String ⇒
            val exception = the[Exception] thrownBy (parse(
              keyValuePairs = Vector(
                ":method" → "POST",
                ":scheme" → scheme,
                ":authority" → authority,
                ":path" → "/"
              )))
            exception.getMessage should startWith("Illegal http2-authority-pseudo-header")
          }
        }
      }

      // The ":path" pseudo-header field includes the path and query parts
      // of the target URI (the "path-absolute" production and optionally a
      // '?' character followed by the "query" production (see Sections 3.3
      // and 3.4 of [RFC3986]).

      "follow RFC3986 for the ':path' pseudo-header" should {

        def parsePath(path: String, uriParsingMode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Uri = {
          parse(Seq(":method" → "GET", ":scheme" → "https", ":path" → path), uriParsingMode = uriParsingMode).uri
        }

        // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
        //             / "*" / "+" / "," / ";" / "="

        // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"

        // path          = path-abempty    ; begins with "/" or is empty
        //               / path-absolute   ; begins with "/" but not "//"
        //               / path-noscheme   ; begins with a non-colon segment
        //               / path-rootless   ; begins with a segment
        //               / path-empty      ; zero characters
        // path-abempty  = *( "/" segment )
        // path-absolute = "/" [ segment-nz *( "/" segment ) ]
        // path-noscheme = segment-nz-nc *( "/" segment )
        // path-rootless = segment-nz *( "/" segment )
        // path-empty    = 0<pchar>
        // segment       = *pchar
        // segment-nz    = 1*pchar
        // segment-nz-nc = 1*( unreserved / pct-encoded / sub-delims / "@" )
        // ; non-zero-length segment without any colon ":"
        // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"

        val pchar: Seq[Char] = {
          // RFC 3986, 2.3. Unreserved Characters
          // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
          val alphaDigit = for ((min, max) ← Seq(('a', 'z'), ('A', 'Z'), ('0', '9')); c ← min to max) yield c
          val unreserved = alphaDigit ++ Seq('-', '.', '_', '~')

          // RFC 3986, 2.2. Reserved Characters
          // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
          //             / "*" / "+" / "," / ";" / "="
          val subDelims = Seq('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')

          // RFC 3986, 3.3. Path
          // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
          unreserved ++ subDelims ++ Seq(':', '@')
        }

        val absolutePaths = Seq[(String, String)](
          "/" → "/",
          "/foo" → "/foo", "/foo/" → "/foo/", "/foo//" → "/foo//", "/foo///" → "/foo///",
          "/foo/bar" → "/foo/bar", "/foo//bar" → "/foo//bar", "/foo//bar/" → "/foo//bar/",
          "/a=b" → "/a=b", "/%2f" → "/%2F", "/x:0/y:1" → "/x:0/y:1"
        ) ++ pchar.map {
            case '.' ⇒ "/." → "/"
            case c   ⇒ ("/" + c) → ("/" + c)
          }

        "parse a ':path' containing a 'path-absolute'" in {
          forAll(absolutePaths) {
            case (input, output) ⇒
              val uri = parsePath(input)
              uri.path.toString should ===(output)
              uri.rawQueryString should ===(None)
          }
        }

        "reject a ':path' that doesn't start with a 'path-absolute'" in {
          val invalidAbsolutePaths = Seq(
            "/ ", "x", "1", "%2f", "-", ".", "_", "~",
            "?", "&", "=", "#", ":", "?", "#", "[", "]", "@", " ",
            "http://localhost/foo"
          )
          forAll(invalidAbsolutePaths) { absPath: String ⇒
            val exception = the[ParsingException] thrownBy (parsePath(absPath))
            exception.getMessage should include("http2-path-pseudo-header")
          }
        }

        "reject a ':path' that doesn't start with a 'path-absolute' (not planning to fix)" in pendingUntilFixed {
          val invalidAbsolutePaths = Seq(
            // Illegal for path-absolute in RFC3986 to start with multiple slashes
            "//", "//x"
          )
          forAll(invalidAbsolutePaths) { absPath: String ⇒
            val exception = the[ParsingException] thrownBy (parsePath(absPath, uriParsingMode = Uri.ParsingMode.Strict))
            exception.getMessage should include("http2-path-pseudo-header")
          }
        }

        // query       = *( pchar / "/" / "?" )

        "parse a ':path' containing a 'query'" in {
          val queryChar: Seq[Char] = pchar ++ Seq('/', '?')

          val queries: Seq[(String, Option[Uri.Query])] = Seq(
            "" → None,
            "name=ferret" → Some(Uri.Query("name" → "ferret")),
            "name=ferret&color=purple" → Some(Uri.Query("name" → "ferret", "color" → "purple")),
            "field1=value1&field2=value2&field3=value3" → Some(Uri.Query("field1" → "value1", "field2" → "value2", "field3" → "value3")),
            "field1=value1&field1=value2&field2=value3" → Some(Uri.Query("field1" → "value1", "field1" → "value2", "field2" → "value3")),
            "first=this+is+a+field&second=was+it+clear+%28already%29%3F" → Some(Uri.Query("first" → "this is a field", "second" → "was it clear (already)?")),
            "e0a72cb2a2c7" → None
          ) ++ queryChar.map((c: Char) ⇒ (c.toString → None))

          forAll(absolutePaths.take(3)) {
            case (inputPath, expectedOutputPath) ⇒
              forAll(queries) {
                case (rawQueryString, optParsedQuery) ⇒
                  val uri = parsePath(inputPath + "?" + rawQueryString)
                  uri.path.toString should ===(expectedOutputPath)
                  uri.rawQueryString should ===(Some(rawQueryString))

                  // How form-encoded query strings are parsed is not strictly part of the HTTP/2 and URI RFCs,
                  // but lets do a quick sanity check to ensure that form-encoded query strings are correctly
                  // parsed into values further up the parsing stack.
                  optParsedQuery.foreach { expectedParsedQuery: Uri.Query ⇒
                    uri.query() should contain theSameElementsAs (expectedParsedQuery)
                  }
              }
          }
        }

        "reject a ':path' containing an invalid 'query'" in pendingUntilFixed {
          val invalidQueries: Seq[String] = Seq(
            ":", "/", "?", "#", "[", "]", "@", " "
          )
          forAll(absolutePaths.take(3)) {
            case (inputPath, _) ⇒
              forAll(invalidQueries) { query: String ⇒
                shouldThrowMalformedRequest(parsePath(inputPath + "?" + query, uriParsingMode = Uri.ParsingMode.Strict))
              }
          }
        }

      }

      // ... A request in asterisk form includes the
      // value '*' for the ":path" pseudo-header field.

      "handle a ':path' with an asterisk" in pendingUntilFixed {
        val request: HttpRequest = parse(
          keyValuePairs = Vector(
            ":method" → "OPTIONS",
            ":scheme" → "http",
            ":path" → "*"
          ))
        request.uri.toString should ===("*") // FIXME: Compare in a better way
      }

      // [The ":path"] pseudo-header field MUST NOT be empty for "http" or "https"
      // URIs...

      "reject empty ':path' pseudo-headers for http and https" in pendingUntilFixed {
        val schemes = Seq("http", "https")
        forAll(schemes) { scheme: String ⇒
          shouldThrowMalformedRequest(parse(
            keyValuePairs = Vector(
              ":method" → "POST",
              ":scheme" → scheme,
              ":path" → ""
            )))
        }
      }

      // The exception to this rule is an
      // OPTIONS request for an "http" or "https" URI that does not include
      // a path component; these MUST include a ":path" pseudo-header field
      // with a value of '*' (see [RFC7230], Section 5.3.4).

      // [already tested above]

      // All HTTP/2 requests MUST include exactly one valid value for the
      // ":method", ":scheme", and ":path" pseudo-header fields, unless it is
      // a CONNECT request (Section 8.3).  An HTTP request that omits
      // mandatory pseudo-header fields is malformed

      // [assume CONNECT not supported]

      "reject requests without a mandatory pseudo-headers" in {
        val mandatoryPseudoHeaders = Seq(":method", ":scheme", ":path")
        forAll(mandatoryPseudoHeaders) { name: String ⇒
          val thrown = shouldThrowMalformedRequest(parse(
            keyValuePairs = Vector(
              ":scheme" → "https",
              ":method" → "GET",
              ":path" → "/"
            ).filter(_._1 != name)))
          thrown.getMessage should ===(s"Malformed request: Mandatory pseudo-header '$name' missing")
        }
      }

      "reject requests with more than one pseudo-header" in {
        val pseudoHeaders = Seq(":method", ":scheme", ":path", ":authority")
        forAll(pseudoHeaders) { name: String ⇒
          val thrown = shouldThrowMalformedRequest(parse(
            keyValuePairs = Vector(
            ":scheme" → "https",
            ":method" → "GET",
            ":authority" → "akka.io",
            ":path" → "/"
          ) :+ (name → "foo")))
          thrown.getMessage should ===(s"Malformed request: Pseudo-header '$name' must not occur more than once")
        }
      }

      // 8.1.2.5.  Compressing the Cookie Header Field

      // If there are multiple Cookie header fields after
      // decompression, these MUST be concatenated into a single octet string
      // using the two-octet delimiter of 0x3B, 0x20 (the ASCII string "; ")
      // before being passed into ... a generic HTTP server application.

      "compress multiple 'cookie' headers into one modeled header" in {
        val cookieHeaders: Seq[(Seq[String], String)] = Vector(
          Seq("a=b") → "a=b",
          Seq("a=b", "c=d") → "a=b; c=d",
          Seq("a=b", "c=d", "e=f") → "a=b; c=d; e=f",
          Seq("a=b; c=d", "e=f") → "a=b; c=d; e=f",
          Seq("a=b", "c=d; e=f") → "a=b; c=d; e=f"
        )
        forAll(cookieHeaders) {
          case (inValues, outValue) ⇒
            val httpRequest: HttpRequest = parse(
              Vector(
                ":method" → "GET",
                ":scheme" → "https",
                ":authority" → "localhost:8000",
                ":path" → "/"
              ) ++ inValues.map("cookie" → _)
            )
            val receivedCookieValues: Seq[String] = httpRequest.headers.collect {
              case c @ Cookie(_) ⇒ c.value
            }
            receivedCookieValues should contain theSameElementsAs Vector(outValue)
        }
      }

      // 8.1.3.  Examples

      "parse GET example" in {
        val request: HttpRequest = parse(
          keyValuePairs = Vector(
            ":method" → "GET",
            ":scheme" → "https",
            ":path" → "/resource",
            "host" → "example.org",
            "accept" → "image/jpeg"
          ))

        request.method should ===(HttpMethods.GET)
        request.uri.scheme should ===("https")
        request.uri.authority.host should ===(Uri.Host(""))
        request.uri.path should ===(Uri.Path./("resource"))
        request.uri.authority.port should ===(0)
        request.uri.authority.userinfo should ===("")
        request.headers should contain theSameElementsAs Vector(
          Http2StreamIdHeader(1),
          Host(Uri.Host("example.org")),
          Accept(MediaRange(MediaTypes.`image/jpeg`))
        )
        request.entity should ===(HttpEntity.Empty)
        request.protocol should ===(HttpProtocols.`HTTP/2.0`)
      }

      "parse POST example" in {
        val request: HttpRequest = parse(
          keyValuePairs = Vector(
            ":method" → "POST",
            ":scheme" → "https",
            ":path" → "/resource",
            "content-type" → "image/jpeg",
            "host" → "example.org",
            "content-length" → "123"
          ),
          data = Source(Vector(ByteString(Array.fill(123)(0x00.toByte))))
        )

        request.method should ===(HttpMethods.POST)
        request.uri.scheme should ===("https")
        request.uri.authority.host should ===(Uri.Host(""))
        request.uri.path should ===(Uri.Path./("resource"))
        request.uri.authority.port should ===(0)
        request.uri.authority.userinfo should ===("")
        request.headers should contain theSameElementsAs Vector(
          Http2StreamIdHeader(1),
          Host(Uri.Host("example.org"))
        )
        inside(request.entity) {
          case entity: HttpEntity.Default ⇒
            entity.contentLength should ===(123.toLong)
            entity.contentType should ===(ContentType(MediaTypes.`image/jpeg`))
        }
        request.protocol should ===(HttpProtocols.`HTTP/2.0`)
      }

    }

    // Tests that don't come from an RFC document...

    "parse GET https://localhost:8000/ correctly" in {
      val request: HttpRequest = parse(
        keyValuePairs = Vector(
          ":method" → "GET",
          ":scheme" → "https",
          ":authority" → "localhost:8000",
          ":path" → "/"
        ))

      request.method should ===(HttpMethods.GET)
      request.uri.scheme should ===("https")
      request.uri.authority.host should ===(Uri.Host("localhost"))
      request.uri.authority.port should ===(8000)
      request.uri.authority.userinfo should ===("")
      request.headers should contain theSameElementsAs Vector(
        Http2StreamIdHeader(1)
      )
      request.entity should ===(HttpEntity.Empty)
      request.protocol should ===(HttpProtocols.`HTTP/2.0`)
    }

  }
}
