package akka.pattern.scaladsl

import akka.pattern.AlwaysFailingResponse
import akka.pattern.internal.FaultyResponseMarker

/**
 * A response message that may cause ask to fail with a [[RuntimeException]] if `failureDescription` returns a an error
 * description. The description will then be passed as the message of the exception.
 *
 * See also [[AlwaysFailingResponse]]
 */
trait MaybeFailingResponse extends FaultyResponseMarker {

  /**
   * @return `None` for a successful message, which will then complete the future as is, or a description that will
   *        then cause the `ask` to fail.
   */
  def failureDescription: Option[String]
}
