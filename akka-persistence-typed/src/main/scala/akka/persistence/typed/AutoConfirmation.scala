/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.Done
import akka.actor.typed.ActorRef

/**
 * A `Done` response message is automatically sent when commands implement this trait.
 * The reply is sent after events in the returned effects have been persisted.
 * The reply is only sent if the effects contains events to be persisted. For example
 * stashing a command and returning `Effect.none` will not send a reply until the
 * command is unstashed and actually handled.
 * The reply is sent to the given [[AutoConfirmation#replyTo]] `ActorRef`, which can be the `replyTo`
 * for an `ask` request.
 */
trait AutoConfirmation {

  def replyTo: ActorRef[Done]

}
