/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

/**
 * Envelope that is published on the eventStream for every message that is
 * dropped due to overfull queues.
 */
final case class Dropped(msg: Any, recipient: ActorRef[Nothing])

/**
 * Exception that an actor fails with if it does not handle a Terminated message.
 */
final case class DeathPactException(ref: ActorRef[Nothing]) extends RuntimeException(s"death pact with $ref was triggered")

/**
 * Envelope for dead letters.
 */
final case class DeadLetter(msg: Any)

/**
 * System signals are notifications that are generated by the system and
 * delivered to the Actor behavior in a reliable fashion (i.e. they are
 * guaranteed to arrive in contrast to the at-most-once semantics of normal
 * Actor messages).
 */
sealed trait Signal

/**
 * Lifecycle signal that is fired upon creation of the Actor. This will be the
 * first message that the actor processes.
 */
sealed abstract class PreStart extends Signal
final case object PreStart extends PreStart {
  def instance: PreStart = this
}

/**
 * Lifecycle signal that is fired upon restart of the Actor before replacing
 * the behavior with the fresh one (i.e. this signal is received within the
 * behavior that failed). The replacement behavior will receive PreStart as its
 * first signal.
 */
sealed abstract class PreRestart extends Signal
final case object PreRestart extends PreRestart {
  def instance: PreRestart = this
}

/**
 * Lifecycle signal that is fired after this actor and all its child actors
 * (transitively) have terminated. The [[Terminated]] signal is only sent to
 * registered watchers after this signal has been processed.
 *
 * <b>IMPORTANT NOTE:</b> if the actor terminated by switching to the
 * `Stopped` behavior then this signal will be ignored (i.e. the
 * Stopped behavior will do nothing in reaction to it).
 */
sealed abstract class PostStop extends Signal
final case object PostStop extends PostStop {
  def instance: PostStop = this
}

/**
 * Lifecycle signal that is fired when an Actor that was watched has terminated.
 * Watching is performed by invoking the
 * [[akka.typed.ActorContext]] `watch` method. The DeathWatch service is
 * idempotent, meaning that registering twice has the same effect as registering
 * once. Registration does not need to happen before the Actor terminates, a
 * notification is guaranteed to arrive after both registration and termination
 * have occurred. Termination of a remote Actor can also be effected by declaring
 * the Actor’s home system as failed (e.g. as a result of being unreachable).
 */
final case class Terminated(ref: ActorRef[Nothing])(failed: Throwable) extends Signal {
  def wasFailed: Boolean = failed ne null
  def failure: Throwable = failed // TODO is this needed? only return Option
  def failureOption: Option[Throwable] = Option(failed)
}
