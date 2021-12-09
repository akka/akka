/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.entity

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Future

import akka.actor.ActorRefProvider
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Entity
import akka.actor.typed.EntityEnvelope
import akka.actor.typed.EntityRef
import akka.actor.typed.EntityTypeKey
import akka.actor.typed.EntityTypeKeyImpl
import akka.actor.typed.internal.InternalRecipientRef
import akka.actor.typed.internal.adapter.ActorRefAdapter
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi
import akka.pattern.AskTimeoutException
import akka.pattern.PromiseActorRef
import akka.pattern.StatusReply
import akka.util.ByteString
import akka.util.Timeout

/**
 * Marker interface to use with dynamic access
 *
 * INTERNAL API
 */
@InternalApi
private[akka] trait EntityProvider {

  def initEntity[M, E](entity: Entity[M, E]): ActorRef[E]

  def entityRefFor[M](typeKey: EntityTypeKey[M], entityId: String): EntityRef[M]
}

private[akka] class LocalEntityProvider(system: ActorSystem[_]) extends EntityProvider {

  private val typeNames: ConcurrentHashMap[String, String] = new ConcurrentHashMap
  private val entityManagers: ConcurrentHashMap[String, ActorRef[_]] = new ConcurrentHashMap
  private val hasExtractor: ConcurrentHashMap[String, Boolean] = new ConcurrentHashMap()

  override def initEntity[M, E](entity: Entity[M, E]): ActorRef[E] = {

    val typeKey = entity.typeKey
    val messageClassName = typeKey.asInstanceOf[EntityTypeKeyImpl[M]].messageClassName

    typeNames.putIfAbsent(typeKey.name, messageClassName) match {
      case existingMessageClassName: String if messageClassName != existingMessageClassName =>
        throw new IllegalArgumentException(s"[${typeKey.name}] already initialized for [$existingMessageClassName]")
      case _ => ()
    }

    hasExtractor.putIfAbsent(typeKey.name, entity.messageExtractor.isDefined)

    val encodedEntityName = URLEncoder.encode(entity.typeKey.name, ByteString.UTF_8)

    val entityManager =
      entityManagers.computeIfAbsent(
        encodedEntityName,
        new java.util.function.Function[String, ActorRef[_]] {
          override def apply(t: String): ActorRef[_] = {
            system.systemActorOf(
              EntityManager.behavior(entity),
              // we don't have two levels like in sharding (sharding/region)
              // but only an entity manager per declared entity.
              // It's path is therefore set to entity-manager-{entity-name}
              "entity-manager-" + encodedEntityName)
          }
        })
    entityManager.asInstanceOf[ActorRef[E]]
  }

  override def entityRefFor[M](typeKey: EntityTypeKey[M], entityId: String): EntityRef[M] = {
    val managerRef = entityManagers.get(typeKey.name).asInstanceOf[ActorRef[Any]]
    EntityRefImpl[M](entityId, typeKey, managerRef)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case class EntityRefImpl[M](
      entityId: String,
      typeKey: EntityTypeKey[M],
      entityManagerRef: ActorRef[Any])
      extends EntityRef[M]
      with InternalRecipientRef[M] {

    /**
     * Converts incoming message to what the EntityManager expects.
     * It may be returned as is or wrapped in an EntityEnvelope.
     */
    private def toEntityMessage(msg: M): Any =
      if (hasExtractor.get(typeKey.name)) msg
      else EntityEnvelope(entityId, msg)

    override def tell(msg: M): Unit =
      entityManagerRef ! toEntityMessage(msg)

    override val refPrefix = EntityManager.encodeEntityId(typeKey, entityId)

    override def ask[Res](message: ActorRef[Res] => M)(implicit timeout: Timeout): Future[Res] = {
      val replyTo = new EntityPromiseRef[Res](timeout)
      replyTo.ask(message(replyTo.ref))
    }

    override def askWithStatus[Res](func: ActorRef[StatusReply[Res]] => M)(implicit timeout: Timeout): Future[Res] =
      StatusReply.flattenStatusFuture(ask[StatusReply[Res]](func))

    override def provider: ActorRefProvider =
      entityManagerRef.asInstanceOf[InternalRecipientRef[_]].provider

    override def isTerminated: Boolean =
      entityManagerRef.asInstanceOf[InternalRecipientRef[_]].isTerminated

    override def hashCode(): Int =
      // 3 and 5 chosen as primes which are +/- 1 from a power-of-two
      ((entityId.hashCode * 3) + typeKey.hashCode) * 5

    override def equals(other: Any): Boolean =
      other match {
        case eri: EntityRefImpl[_] => (eri.entityId == entityId) && (eri.typeKey == typeKey)
        case _                     => false
      }

    override def toString: String = s"EntityRef($typeKey, $entityId)"

    /** Similar to [[akka.actor.typed.scaladsl.AskPattern.PromiseRef]] and to
     * akka.cluster.sharding.typed.internal.EntityRefImpl.EntityPromiseRef
     * but for a local `EntityRef` target.
     */
    @InternalApi
    private final class EntityPromiseRef[U](timeout: Timeout) {

      // Note: _promiseRef mustn't have a type pattern, since it can be null
      private[this] val (_ref: ActorRef[U], _future: Future[U], _promiseRef) =
        if (isTerminated)
          (
            ActorRefAdapter[U](provider.deadLetters),
            Future.failed[U](
              new AskTimeoutException(s"Recipient of [${EntityRefImpl.this}] had already been terminated.")),
            null)
        else if (timeout.duration.length <= 0)
          (
            ActorRefAdapter[U](provider.deadLetters),
            Future.failed[U](
              new IllegalArgumentException(
                s"Timeout length must be positive, question not sent to [${EntityRefImpl.this}]")),
            null)
        else {
          // note that the real messageClassName will be set afterwards, replyTo pattern
          val a =
            PromiseActorRef(provider, timeout, targetName = EntityRefImpl.this, messageClassName = "unknown", refPrefix)
          val b = ActorRefAdapter[U](a)
          (b, a.result.future.asInstanceOf[Future[U]], a)
        }

      val ref: ActorRef[U] = _ref
      val future: Future[U] = _future

      @InternalStableApi
      private[akka] def ask(message: M): Future[U] = {
        if (_promiseRef ne null) _promiseRef.messageClassName = message.getClass.getName
        entityManagerRef ! toEntityMessage(message)
        future
      }
    }
  }

}
