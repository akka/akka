package akka.http.model

/** The protocol of an HTTP message */
trait HttpProtocol

object HttpProtocols {
  def `HTTP/1.1`: HttpProtocol = ???
}
