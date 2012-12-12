package akka.remote

import java.util.concurrent.TimeUnit._

/**
 * A failure detector must be a thread-safe mutable construct that registers heartbeat events of a resource and is able to
 * decide the availability of that monitored resource.
 */
trait FailureDetector {

  /**
   * Returns true if the resource is considered to be up and healthy and returns false otherwise.
   */
  def isAvailable: Boolean

  /**
   * Notifies the FailureDetector that a heartbeat arrived from the monitored resource. This causes the FailureDetector
   * to update its state.
   */
  def heartbeat(): Unit

}

object FailureDetector {

  /**
   * Abstraction of a clock that returns time in milliseconds. Clock can only be used to measure elapsed
   * time and is not related to any other notion of system or wall-clock time.
   */
  abstract class Clock extends (() ⇒ Long)

  implicit val defaultClock = new Clock {
    def apply() = NANOSECONDS.toMillis(System.nanoTime)
  }
}
