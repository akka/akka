/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.entity

import java.net.URLEncoder

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Entity
import akka.actor.typed.Entity.Passivate
import akka.actor.typed.EntityContext
import akka.actor.typed.EntityEnvelope
import akka.actor.typed.EntityTypeKey
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.util.ByteString

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EntityManager {

  def encodeEntityId(typeKey: EntityTypeKey[_], entityId: String) =
    URLEncoder.encode(s"${typeKey.name}-$entityId", ByteString.UTF_8)

  def behavior[M, E](entity: Entity[M, E]): Behavior[Any] = {

    // key will be the entityName and buffer vector contains an envelope so we keep track of entityID
    var entityMessageBuffers: Map[String, Vector[EntityEnvelope[M]]] = Map.empty

    Behaviors.setup[Any] { ctx =>
      def lookupEntityRef(id: String) = {

        val entityName = encodeEntityId(entity.typeKey, id)
        val entityRef =
          ctx.child(entityName) match {
            case Some(entityChildRef) =>
              ctx.log.debug("Found entity instance {}", entityChildRef.path)
              entityChildRef
            case None =>
              ctx.log.debug("No entity found, creating one..")
              val entityContext = new EntityContext[M](entity.typeKey, id, ctx.self)
              ctx.spawn(entity.createBehavior(entityContext), entityName)
          }

        ctx.watch(entityRef)
        entityRef.asInstanceOf[ActorRef[M]]
      }

      def deliverToEntity(entityId: String, message: M) = {
        val entityName = encodeEntityId(entity.typeKey, entityId)

        entityMessageBuffers.get(entityName) match {
          case Some(buffer) => // if it has a buffer, it means that entity is passivating
            val newBuffer = buffer :+ EntityEnvelope(entityId, message)
            entityMessageBuffers = entityMessageBuffers + (entityName -> newBuffer)
          case None =>
            lookupEntityRef(entityId) ! message
        }
      }

      Behaviors
        .receiveMessage[Any] {

          case Passivate(actor: ActorRef[M] @unchecked) if ctx.child(actor.path.name).isDefined =>
            ctx.log.debug("Received passivation request for {}", actor)
            // start to buffer message for this entity
            entityMessageBuffers = entityMessageBuffers + (actor.path.name -> Vector.empty)

            entity.stopMessage match {
              case Some(stopMsg) =>
                actor ! stopMsg
                Behaviors.same
              case None =>
                ctx.stop(actor)
                Behaviors.same
            }

          case env: EntityEnvelope[M] @unchecked =>
            deliverToEntity(env.entityId, env.message)
            Behaviors.same

          case unwrapped: E @unchecked if entity.messageExtractor.isDefined =>
            val msgExtractor = entity.messageExtractor.get
            val id = msgExtractor.entityId(unwrapped)
            val msg = msgExtractor.unwrapMessage(unwrapped)
            deliverToEntity(id, msg)
            Behaviors.same

          case _ => Behaviors.unhandled

        }
        .receiveSignal {
          case (_, Terminated(actor)) =>
            val entityName = actor.path.name
            entityMessageBuffers.get(entityName).foreach { buffer =>
              // re-instantiate it and deliver messages directly, if any
              if (buffer.nonEmpty) {

                ctx.log.debug("Re-instantiating entity for id {}", buffer.head.entityId)
                val entityRef = lookupEntityRef(buffer.head.entityId)

                ctx.log.debug("Delivering {} buffered messages to entity {}", buffer.length, entityRef.path)
                entityRef ! buffer.head.message

                buffer.tail.foreach { env =>
                  entityRef ! env.message
                }
              }
            }

            // all messages are delivered (if any), we can remove buffer for this entity
            entityMessageBuffers = entityMessageBuffers - entityName

            Behaviors.same
        }

    }
  }
}
