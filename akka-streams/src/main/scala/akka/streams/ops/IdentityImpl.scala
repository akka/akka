package akka.streams
package ops

import akka.streams.Operation.Identity

object IdentityImpl extends OpInstance[Any, Nothing] {
  def handle(result: SimpleResult[Any]): Result[Nothing] = result.asInstanceOf[Result[Nothing]]
}
