/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{ Message, UpgradeToWebsocket }
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpProtocol, HttpHeader }
import akka.parboiled2.util.Base64
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

import scala.collection.immutable.Seq
import scala.reflect.ClassTag

/**
 * Server-side implementation of the Websocket handshake
 *
 * INTERNAL API
 */
private[http] object Handshake {
  val CurrentWebsocketVersion = 13

  /*
      From: http://tools.ietf.org/html/rfc6455#section-4.2.1

      1.   An HTTP/1.1 or higher GET request, including a "Request-URI"
            [RFC2616] that should be interpreted as a /resource name/
            defined in Section 3 (or an absolute HTTP/HTTPS URI containing
            the /resource name/).

       2.   A |Host| header field containing the server's authority.

       3.   An |Upgrade| header field containing the value "websocket",
            treated as an ASCII case-insensitive value.

       4.   A |Connection| header field that includes the token "Upgrade",
            treated as an ASCII case-insensitive value.

       5.   A |Sec-WebSocket-Key| header field with a base64-encoded (see
            Section 4 of [RFC4648]) value that, when decoded, is 16 bytes in
            length.

       6.   A |Sec-WebSocket-Version| header field, with a value of 13.

       7.   Optionally, an |Origin| header field.  This header field is sent
            by all browser clients.  A connection attempt lacking this
            header field SHOULD NOT be interpreted as coming from a browser
            client.

       8.   Optionally, a |Sec-WebSocket-Protocol| header field, with a list
            of values indicating which protocols the client would like to
            speak, ordered by preference.

       9.   Optionally, a |Sec-WebSocket-Extensions| header field, with a
            list of values indicating which extensions the client would like
            to speak.  The interpretation of this header field is discussed
            in Section 9.1.
   */
  def isWebsocketUpgrade(headers: List[HttpHeader], hostHeaderPresent: Boolean): Option[UpgradeToWebsocket] = {
    def find[T <: HttpHeader: ClassTag]: Option[T] =
      headers.collectFirst {
        case t: T ⇒ t
      }

    val host = find[Host]
    val upgrade = find[Upgrade]
    val connection = find[Connection]
    val key = find[`Sec-WebSocket-Key`]
    val version = find[`Sec-WebSocket-Version`]
    val origin = find[Origin]
    val protocol = find[`Sec-WebSocket-Protocol`]
    val supportedProtocols = protocol.toList.flatMap(_.protocols)
    val extensions = find[`Sec-WebSocket-Extensions`]

    def isValidKey(key: String): Boolean = Base64.rfc2045().decode(key).length == 16

    if (upgrade.exists(_.hasWebsocket) &&
      connection.exists(_.hasUpgrade) &&
      version.exists(_.hasVersion(CurrentWebsocketVersion)) &&
      key.exists(k ⇒ isValidKey(k.key))) {

      val header = new UpgradeToWebsocketLowLevel {
        def requestedProtocols: Seq[String] = supportedProtocols

        def handleFrames(handlerFlow: Flow[FrameEvent, FrameEvent, Any], subprotocol: Option[String]): HttpResponse = {
          require(subprotocol.forall(chosen ⇒ supportedProtocols.contains(chosen)),
            s"Tried to choose invalid subprotocol '$subprotocol' which wasn't offered by the client: [${requestedProtocols.mkString(", ")}]")
          buildResponse(key.get, handlerFlow, subprotocol)
        }
      }
      Some(header)
    } else None
  }

  /*
    From: http://tools.ietf.org/html/rfc6455#section-4.2.2

    1.  A Status-Line with a 101 response code as per RFC 2616
        [RFC2616].  Such a response could look like "HTTP/1.1 101
        Switching Protocols".

    2.  An |Upgrade| header field with value "websocket" as per RFC
        2616 [RFC2616].

    3.  A |Connection| header field with value "Upgrade".

    4.  A |Sec-WebSocket-Accept| header field.  The value of this
        header field is constructed by concatenating /key/, defined
        above in step 4 in Section 4.2.2, with the string "258EAFA5-
        E914-47DA-95CA-C5AB0DC85B11", taking the SHA-1 hash of this
        concatenated value to obtain a 20-byte value and base64-
        encoding (see Section 4 of [RFC4648]) this 20-byte hash.
   */
  def buildResponse(key: `Sec-WebSocket-Key`, handlerFlow: Flow[FrameEvent, FrameEvent, Any], subprotocol: Option[String]): HttpResponse =
    HttpResponse(
      StatusCodes.SwitchingProtocols,
      subprotocol.map(p ⇒ `Sec-WebSocket-Protocol`(Seq(p))).toList :::
        List(
          Upgrade(List(UpgradeProtocol("websocket"))),
          Connection(List("upgrade")),
          `Sec-WebSocket-Accept`.forKey(key),
          UpgradeToWebsocketResponseHeader(handlerFlow)))
}
