package akka.http.model

/**
 * The model of an HTTP header. In its most basic form headers are simple name-value pairs. Header names
 * are compared in a case-insensitive way.
 */
trait HttpHeader {
  def name: String
  def value: String
  def lowercaseName: String
  def is(nameInLowerCase: String): Boolean = lowercaseName == nameInLowerCase
  def isNot(nameInLowerCase: String): Boolean = !is(nameInLowerCase)
}
