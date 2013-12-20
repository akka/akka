package akka.http.model
package parser

object HttpParser {
  def parseHeader(rawHeader: headers.RawHeader): Either[ErrorInfo, HttpHeader] = ???
}
