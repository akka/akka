package akka.http.impl.engine.http2

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
}
