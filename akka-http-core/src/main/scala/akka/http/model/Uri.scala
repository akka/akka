package akka.http.model

/**
 * The model for a Uri.
 */
trait Uri {
  def isEmpty: Boolean
}
object Uri {
  def / : Uri = ???
}
