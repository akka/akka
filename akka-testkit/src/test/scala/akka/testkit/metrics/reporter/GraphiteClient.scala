/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.metrics.reporter

import java.util.regex.Pattern
import java.nio.charset.Charset
import java.io._
import javax.net.SocketFactory
import java.net.InetSocketAddress

/**
 * Carbon (graphite) client, which can be used to send metrics.
 *
 * The data is sent over a plain Socket, even though it would fit AkkaIO nicely, but this way it has no dependencies.
 */
class GraphiteClient(address: InetSocketAddress) extends Closeable {

  private final val WHITESPACE = Pattern.compile("[\\s]+")
  private final val charset: Charset = Charset.forName("UTF-8")

  private lazy val socket = {
    val s = SocketFactory.getDefault.createSocket(address.getAddress, address.getPort)
    s.setKeepAlive(true)
    s
  }

  private lazy val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, charset))

  /** Send measurement carbon server. Thread-safe. */
  def send(name: String, value: String, timestamp: Long) {
    val sb = new StringBuilder()
      .append(sanitize(name)).append(' ')
      .append(sanitize(value)).append(' ')
      .append(timestamp.toString).append('\n')

    // The write calls below handle the string in-one-go (locking);
    // Whereas the metrics' implementation of the graphite client uses multiple `write` calls,
    // which could become interwoven, thus producing a wrong metric-line, when called by multiple threads.
    writer.write(sb.toString())
    writer.flush()
  }

  /** Closes underlying connection. */
  def close() {
    try socket.close() finally writer.close()
  }

  protected def sanitize(s: String): String = {
    WHITESPACE.matcher(s).replaceAll("-")
  }
}
