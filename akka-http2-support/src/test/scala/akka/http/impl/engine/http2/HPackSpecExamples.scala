package akka.http.impl.engine.http2

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.model.headers.CacheDirectives
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.headers.RawHeader

/**
 * Examples from the HPACK specification. See https://tools.ietf.org/html/rfc7541#appendix-C
 */
object HPackSpecExamples {
  /**
   * C.4.1.  First Request
   *
   *  Header list to encode:
   *
   *  :method: GET
   *  :scheme: http
   *  :path: /
   *  :authority: www.example.com
   *
   *  Hex dump of encoded data:
   *
   *  8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 | ...A......:k....
   *  ff                                      | .
   */
  val C41FirstRequestWithHuffman = "8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff".parseHexByteString

  /**
   * C.4.2.  Second Request
   *
   *  Header list to encode:
   *
   *  :method: GET
   *  :scheme: http
   *  :path: /
   *  :authority: www.example.com
   *  cache-control: no-cache
   *
   *  Hex dump of encoded data:
   *
   *  8286 84be 5886 a8eb 1064 9cbf           | ....X....d..
   */
  val C42SecondRequestWithHuffman = "8286 84be 5886 a8eb 1064 9cbf".parseHexByteString

  /**
   * C.4.3 Third Request
   *
   *  Header list to encode:
   *
   *  :method: GET
   *  :scheme: https
   *  :path: /index.html
   *  :authority: www.example.com
   *  custom-key: custom-value
   *  Hex dump of encoded data:
   *
   *  8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 | ....@.%.I.[.}..%
   *  a849 e95b b8e8 b4bf                     | .I.[....
   */
  val C43ThirdRequestWithHuffman =
    """8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925
       a849 e95b b8e8 b4bf""".parseHexByteString

  /**
   * C.5.2 Second Response
   *
   *  The (":status", "302") header field is evicted from the dynamic table to free space to allow adding the (":status", "307") header field.
   *
   *  Header list to encode:
   *
   *  :status: 307
   *  cache-control: private
   *  date: Mon, 21 Oct 2013 20:13:21 GMT
   *  location: https://www.example.com
   *  Hex dump of encoded data:
   *
   *  4803 3330 37c1 c0bf                     | H.307...
   */
  val C52SecondResponseWithoutHuffman = "4803 3330 37c1 c0bf".parseHexByteString

  /**
   * C.6.1.  First Response
   *
   *  Header list to encode:
   *
   *  :status: 302
   *  cache-control: private
   *  date: Mon, 21 Oct 2013 20:13:21 GMT
   *  location: https://www.example.com
   *
   *  Hex dump of encoded data:
   *
   *  4882 6402 5885 aec3 771a 4b61 96d0 7abe | H.d.X...w.Ka..z.
   *  9410 54d4 44a8 2005 9504 0b81 66e0 82a6 | ..T.D. .....f...
   *  2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 | -..n..)...c.....
   *  e9ae 82ae 43d3                          | ....C.
   */
  val C61FirstResponseWithHuffman =
    """4882 6402 5885 aec3 771a 4b61 96d0 7abe
       9410 54d4 44a8 2005 9504 0b81 66e0 82a6
       2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8
       e9ae 82ae 43d3""".parseHexByteString

  /**
   * C.6.2.  Second Response
   *
   *  The (":status", "302") header field is evicted from the dynamic table
   *  to free space to allow adding the (":status", "307") header field.
   *
   *  Header list to encode:
   *
   *  :status: 307
   *  cache-control: private
   *  date: Mon, 21 Oct 2013 20:13:21 GMT
   *  location: https://www.example.com
   *
   *  Hex dump of encoded data:
   *
   *  4883 640e ffc1 c0bf                     | H.d.....
   */
  val C62SecondResponseWithHuffman = "4883 640e ffc1 c0bf".parseHexByteString

  /**
   * C.6.3 Third Response
   *
   *  Several header fields are evicted from the dynamic table during the processing of this header list.
   *
   *  Header list to encode:
   *
   *  :status: 200
   *  cache-control: private
   *  date: Mon, 21 Oct 2013 20:13:22 GMT
   *  location: https://www.example.com
   *  content-encoding: gzip
   *  set-cookie: foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1
   *
   *  Hex dump of encoded data:
   *
   *   88c1 6196 d07a be94 1054 d444 a820 0595 | ..a..z...T.D. ..
   *   040b 8166 e084 a62d 1bff c05a 839b d9ab | ...f...-...Z....
   *   77ad 94e7 821d d7f2 e6c7 b335 dfdf cd5b | w..........5...[
   *   3960 d5af 2708 7f36 72c1 ab27 0fb5 291f | 9`..'..6r..'..).
   *   9587 3160 65c0 03ed 4ee5 b106 3d50 07   | ..1`e...N...=P.
   */
  val C63ThirdResponseWithHuffman =
    """88c1 6196 d07a be94 1054 d444 a820 0595
       040b 8166 e084 a62d 1bff c05a 839b d9ab
       77ad 94e7 821d d7f2 e6c7 b335 dfdf cd5b
       3960 d5af 2708 7f36 72c1 ab27 0fb5 291f
       9587 3160 65c0 03ed 4ee5 b106 3d50 07""".parseHexByteString

  /**
   * akka-http model representation of first request (as encoded in C.5.1 and C.6.1)
   */
  val FirstResponse =
    HttpResponse(
      302,
      headers = Vector(
        headers.`Cache-Control`(CacheDirectives.`private`()),
        headers.Date.parseFromValueString("Mon, 21 Oct 2013 20:13:21 GMT").right.get,
        headers.Location("https://www.example.com")))

  /**
   * akka-http model representation of second request (as encoded in C.5.2 and C.6.2)
   */
  val SecondResponse =
    HttpResponse(
      307,
      headers = Vector(
        headers.`Cache-Control`(CacheDirectives.`private`()),
        headers.Date.parseFromValueString("Mon, 21 Oct 2013 20:13:21 GMT").right.get,
        headers.Location("https://www.example.com")))

  /**
   * akka-http model representation of second request (as encoded in C.5.3 and C.6.3)
   */
  val ThirdResponse =
    HttpResponse(
      200,
      headers = Vector(
        headers.`Cache-Control`(CacheDirectives.`private`()),
        headers.Date.parseFromValueString("Mon, 21 Oct 2013 20:13:22 GMT").right.get,
        headers.Location("https://www.example.com"),
        headers.`Content-Encoding`(HttpEncodings.gzip),
        // `Set-Cookie` modeled header doesn't support extra params like "version=1"
        RawHeader("Set-Cookie", "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1")))
}
