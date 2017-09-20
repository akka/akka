/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.cluster.sharding

/**
 * A reference to an entityId and the local access to sharding, allows for actor-like interaction
 *
 * The entity ref must be resolved locally and cannot be sent to another node.
 *
 * TODO explain why this is NOT an ActorRef
 */
trait ShardedEntityRef[A] { // TODO naming proposal
  /**
   * Send a message to the entity referenced by this ShardedEntityRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: A): Unit

  // TODO we need ask too
}

object ShardedEntityRef {
  implicit final class ShardedEntityRefOps[T](val ref: ShardedEntityRef[T]) extends AnyVal {
    /**
     * Send a message to the Actor referenced by this ActorRef using *at-most-once*
     * messaging semantics.
     */
    def !(msg: T): Unit = ref.tell(msg)

    // TODO we need ask here tooo
  }
}
