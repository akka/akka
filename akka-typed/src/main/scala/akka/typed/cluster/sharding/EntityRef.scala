/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.cluster.sharding

import akka.actor.{ InternalActorRef, Scheduler }
import akka.annotation.InternalApi
import akka.pattern.{ AskTimeoutException, PromiseActorRef }
import akka.typed.ActorRef
import akka.typed.scaladsl.AskPattern
import akka.typed.scaladsl.AskPattern.PromiseRef
import akka.util.Timeout

import scala.concurrent.Future

/**
 * A reference to an sharded Entity, which allows `ActorRef`-like usage.
 *
 * An [[EntityRef]] is NOT an [[ActorRef]]–by design–in order to be explicit about the fact that the life-cycle
 * of a sharded Entity is very different than a plain Actors. Most notably, this is shown by features of Entities
 * such as re-balancing (an active Entity to a different node) or passivation. Both of which are aimed to be completely
 * transparent to users of such Entity. In other words, if this were to be a plain ActorRef, it would be possible to
 * apply DeathWatch to it, which in turn would then trigger when the sharded Actor stopped, breaking the illusion that
 * Entity refs are "always there". Please note that while not encouraged, it is possible to expose an Actor's `self`
 * [[ActorRef]] and watch it in case such notification is desired.
 */
trait EntityRef[A] {

  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: A): Unit

  /**
   * Allows to "ask" the [[EntityRef]] for a reply.
   * See [[akka.typed.scaladsl.AskPattern]] for a complete write-up of this pattern
   *
   * Example usage:
   * {{{
   * case class Request(msg: String, replyTo: ActorRef[Reply])
   * case class Reply(msg: String)
   *
   * implicit val timeout = Timeout(3.seconds)
   * val target: EntityRef[Request] = ...
   * val f: Future[Reply] = target ? (Request("hello", _))
   * }}}
   *
   * Please note that an implicit [[akka.util.Timeout]] and [[akka.actor.Scheduler]] must be available to use this pattern.
   */
  def ask[U](f: ActorRef[U] ⇒ A)(implicit timeout: Timeout, scheduler: Scheduler): Future[U]

}

object EntityRef {
  implicit final class EntityRefOps[A](val ref: EntityRef[A]) extends AnyVal {
    /**
     * Send a message to the Actor referenced by this ActorRef using *at-most-once*
     * messaging semantics.
     */
    def !(msg: A): Unit = ref.tell(msg)

    /**
     * Allows to "ask" the [[EntityRef]] for a reply.
     * See [[akka.typed.scaladsl.AskPattern]] for a complete write-up of this pattern
     *
     * Example usage:
     * {{{
     * case class Request(msg: String, replyTo: ActorRef[Reply])
     * case class Reply(msg: String)
     *
     * implicit val timeout = Timeout(3.seconds)
     * val target: EntityRef[Request] = ...
     * val f: Future[Reply] = target ? (Request("hello", _))
     * }}}
     *
     * Please note that an implicit [[akka.util.Timeout]] and [[akka.actor.Scheduler]] must be available to use this pattern.
     */
    def ?[U](f: ActorRef[U] ⇒ A)(implicit timeout: Timeout, scheduler: Scheduler): Future[U] =
      ref.ask(f)(timeout, scheduler)
  }
}

@InternalApi
private[akka] final class AdaptedEntityRefImpl[A](shardRegion: akka.actor.ActorRef, entityId: String) extends EntityRef[A] {
  import akka.pattern.ask

  override def tell(msg: A): Unit =
    shardRegion ! ShardingEnvelope(entityId, msg)

  override def ask[U](f: (ActorRef[U]) ⇒ A)(implicit timeout: Timeout, scheduler: Scheduler): Future[U] = {
    import akka.typed._
    val p = new EntityPromiseRef[U](shardRegion.asInstanceOf[InternalActorRef], timeout)
    val m = f(p.ref)
    if (p.promiseRef ne null) p.promiseRef.messageClassName = m.getClass.getName
    shardRegion ! ShardingEnvelope(entityId, m)
    p.future
  }

  /** Similar to [[akka.typed.scaladsl.AskPattern.PromiseRef]] but for an [[EntityRef]] target. */
  @InternalApi
  private final class EntityPromiseRef[U](untyped: InternalActorRef, timeout: Timeout) {
    import akka.typed.internal.{ adapter ⇒ adapt }

    // Note: _promiseRef mustn't have a type pattern, since it can be null
    private[this] val (_ref: ActorRef[U], _future: Future[U], _promiseRef) =
      if (untyped.isTerminated)
        (
          adapt.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new AskTimeoutException(s"Recipient[$untyped] had already been terminated.")),
          null)
      else if (timeout.duration.length <= 0)
        (
          adapt.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new IllegalArgumentException(s"Timeout length must be positive, question not sent to [$untyped]")),
          null
        )
      else {
        val a = PromiseActorRef(untyped.provider, timeout, untyped, "unknown")
        val b = adapt.ActorRefAdapter[U](a)
        (b, a.result.future.asInstanceOf[Future[U]], a)
      }

    val ref: ActorRef[U] = _ref
    val future: Future[U] = _future
    val promiseRef: PromiseActorRef = _promiseRef
  }

}
