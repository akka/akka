package akka.remote

/**
 * A failure detector is a thread-safe mutable construct that registers heartbeat events of a resource and is able to
 * give verdict about the availability of that monitored resource.
 */
trait FailureDetector {

  /**
   * Returns true if the resource is considered to be up and healthy and returns false otherwise.
   */
  def isAvailable: Boolean

  /**
   * Records a heartbeat event.
   */
  def heartbeat(): Unit

}
