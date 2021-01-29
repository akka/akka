/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate.scaladsl

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.serialization.jackson.CborSerializable

class DurableStateBehaviorSpec {

  trait Command extends CborSerializable

  trait Response extends CborSerializable
  case class MissingOrder(id: String, msg: String) extends Response
  case class CurrentOrder(id: String, item: Map[String, Int]) extends Response

  case class CreateOrder(id: String, items: Map[String, Int]) extends Command
  case class ReadOrder(id: String, replyTo: ActorRef[Response]) extends Command
  case class UpdateOrder(id: String, items: Map[String, Int]) extends Command
  case class DeleteOrder(id: String, replyTo: ActorRef[String]) extends Command

  sealed trait Order extends CborSerializable
  case object EmptyOrder extends Order

  case class Item(id: String, quantity: Int) extends CborSerializable
  case class PlacedOrder(id: String, items: List[Item]) extends Order

  def create(id: String): DurableStateBehavior[Command, Order] =
    DurableStateBehavior(
      persistenceId = PersistenceId("order", id),
      emptyState = EmptyOrder,
      commandHandler = {
        case (EmptyOrder, cmd)         => createOrder(cmd)
        case (order: PlacedOrder, cmd) => applyCommand(order, cmd)
      })

  def createOrder(cmd: Command): Effect[Order] =
    cmd match {
      case CreateOrder(id, itemsMap) =>
        val items = itemsMap.map { case (id, quantity) =>
          Item(id, quantity)
        }.toList
        Effect.persist(PlacedOrder(id, items))

      case ReadOrder(id, replyTo) =>
        Effect.none.thenReply(replyTo) { r =>
          MissingOrder(id, s"Order $id not found")
        }

      case _: UpdateOrder => Effect.unhandled
      case _: DeleteOrder => Effect.unhandled
    }

  def applyCommand(order: PlacedOrder, cmd: Command): Effect[Order] =
    cmd match {
      case _: CreateOrder => Effect.unhandled

      case ReadOrder(id, replyTo) =>
        val itemsMap = order.items.map(i => (i.id, i.quantity)).toMap
        Effect.none.thenReply(replyTo) { _ => CurrentOrder(id, itemsMap) }

      case UpdateOrder(id, itemsMap) =>
        val items = itemsMap.map { case (id, quantity) =>
          Item(id, quantity)
        }.toList
        Effect.persist(order.copy(items = items))

      case DeleteOrder(id, replyTo) =>
        Effect.delete().thenReply(replyTo) { s =>
          "Order is deleted"
        }
    }
}
