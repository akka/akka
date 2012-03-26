package akka.camel
/**
 * Thrown to indicate that the actor referenced by an endpoint URI cannot be
 * found in the actor system.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {
  override def getMessage = "Actor [%s] doesn't exist" format uri
}
