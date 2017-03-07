package akka.http.impl.util

import akka.NotUsed
import akka.annotation.InternalApi
import akka.stream.TLSProtocol._
import akka.stream.scaladsl
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.util.ByteString

/**
 * INTERNAL API
 *
 * Flow and BidiFlow stages to log streams of ByteString.
 */
@InternalApi
private[akka] object LogByteStringTools {
  val MaxBytesPrinted = 16 * 5

  def logByteStringBidi(name: String, maxBytes: Int = MaxBytesPrinted): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    BidiFlow.fromFlows(
      logByteString(s"$name DOWN", maxBytes),
      logByteString(s"$name UP  ", maxBytes)
    )

  def logToStringBidi[A, B](name: String, maxBytes: Int = MaxBytesPrinted): BidiFlow[A, A, B, B, NotUsed] =
    BidiFlow.fromFlows(
      logToString(s"$name DOWN", maxBytes),
      logToString(s"$name UP  ", maxBytes)
    )

  def logByteString(name: String, maxBytes: Int = MaxBytesPrinted): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].log(name, printByteString(_, maxBytes))

  def logToString[A](name: String, maxBytes: Int = MaxBytesPrinted): Flow[A, A, NotUsed] =
    Flow[A].log(name, _.toString().take(maxBytes))

  def logTLSBidi(name: String, maxBytes: Int = MaxBytesPrinted): BidiFlow[SslTlsOutbound, SslTlsOutbound, SslTlsInbound, SslTlsInbound, NotUsed] =
    BidiFlow.fromFlows(
      logTlsOutbound(s"$name DOWN", maxBytes),
      logTlsInbound(s"$name UP  ", maxBytes))

  def logTlsOutbound(name: String, maxBytes: Int = MaxBytesPrinted): Flow[SslTlsOutbound, SslTlsOutbound, NotUsed] =
    Flow[SslTlsOutbound].log(name, {
      case SendBytes(bytes)       ⇒ "SendBytes " + printByteString(bytes, maxBytes)
      case n: NegotiateNewSession ⇒ n.toString
    })

  def logTlsInbound(name: String, maxBytes: Int = MaxBytesPrinted): Flow[SslTlsInbound, SslTlsInbound, NotUsed] =
    Flow[SslTlsInbound].log(name, {
      case s: SessionTruncated          ⇒ s
      case SessionBytes(session, bytes) ⇒ "SessionBytes " + printByteString(bytes, maxBytes)
    })

  def printByteString(bytes: ByteString, maxBytes: Int = MaxBytesPrinted): String = {
    val indent = " "

    def formatBytes(bs: ByteString): Iterator[String] = {
      def asHex(b: Byte): String = b formatted "%02X"
      def asASCII(b: Byte): Char =
        if (b >= 0x20 && b < 0x7f) b.toChar
        else '.'

      def formatLine(bs: ByteString): String = {
        val hex = bs.map(asHex).mkString(" ")
        val ascii = bs.map(asASCII).mkString
        f"$indent%s  $hex%-48s | $ascii"
      }
      def formatBytes(bs: ByteString): String =
        bs.grouped(16).map(formatLine).mkString("\n")

      val prefix = s"${indent}ByteString(${bs.size} bytes)"

      if (bs.size <= maxBytes * 2) Iterator(prefix + "\n", formatBytes(bs))
      else
        Iterator(
          s"$prefix first + last $maxBytes:\n",
          formatBytes(bs.take(maxBytes)),
          s"\n$indent                    ... [${bs.size - (maxBytes * 2)} bytes omitted] ...\n",
          formatBytes(bs.takeRight(maxBytes)))
    }

    formatBytes(bytes).mkString("")
  }

  def logTLSBidiBySetting(tag: String, maxBytesSetting: Option[Int]): BidiFlow[SslTlsOutbound, SslTlsOutbound, SslTlsInbound, SslTlsInbound, Any] =
    maxBytesSetting
      .map(logTLSBidi(tag, _)).
      getOrElse(BidiFlow.identity)
}
