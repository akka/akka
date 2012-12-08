package akka.camel
/**
 * Thrown to indicate that the actor referenced by an endpoint URI cannot be
 * found in the actor system.
 *
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {
  override def getMessage: String = "Actor [%s] doesn't exist" format uri
}
