/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import java.util.UUID

import akka.actor.PoisonPill
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.persistence.fsm.PersistentFSM.StateChangeEvent
import akka.persistence.fsm.PersistentFSMSpec.DomainEvent
import akka.persistence.fsm.PersistentFSMSpec.EmptyShoppingCart
import akka.persistence.fsm.PersistentFSMSpec.Item
import akka.persistence.fsm.PersistentFSMSpec.ItemAdded
import akka.persistence.fsm.PersistentFSMSpec.OrderDiscarded
import akka.persistence.fsm.PersistentFSMSpec.OrderExecuted
import akka.persistence.fsm.PersistentFSMSpec.ShoppingCart
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.EventSeq
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.PersistentFSMMigration
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures

object PersistentFsmToTypedMigrationSpec {
  val config = ConfigFactory.parseString(s"""
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    """)

}

object ShoppingCartActor {

  def apply(pid: PersistenceId) = behavior(pid)

  sealed trait Command
  case class AddItem(item: Item) extends Command
  case object Buy extends Command
  case object Leave extends Command
  case class GetCurrentCart(replyTo: ActorRef[ShoppingCart]) extends Command with ExpectingReply[ShoppingCart]

  sealed trait State
  case class LookingAround(cart: ShoppingCart) extends State
  case class Shopping(cart: ShoppingCart) extends State
  case class Inactive(cart: ShoppingCart) extends State
  case class Paid(cart: ShoppingCart) extends State

  val persistentFSMSnapshotAdapter = PersistentFSMMigration.snapshotAdapter[State] {
    case (stateIdentifier, data, _) =>
      println(s"PFSM Snapshot: $stateIdentifier $data")
      val cart = data.asInstanceOf[ShoppingCart]
      val state = stateIdentifier match {
        case "Looking Around" => LookingAround(cart)
        case "Shopping"       => Shopping(cart)
        case "Inactive"       => Inactive(cart)
        case "Paid"           => Paid(cart)
        case id               => throw new IllegalStateException(s"Unexpected state identifier $id")
      }
      println("Recovered to state: " + state)
      state
  }

  //#event-adapter
  class PersistentFsmEventAdapter extends EventAdapter[DomainEvent, Any] {
    override def toJournal(e: DomainEvent): Any = e
    override def manifest(event: DomainEvent): String = ""
    override def fromJournal(journalEvent: Any, manifest: String): EventSeq[DomainEvent] = {
      journalEvent match {
        case _: StateChangeEvent =>
          // Alternatively this could be converted into a private event if the state
          // information is required as it can't be inferred from the events
          EventSeq.empty
        case other =>
          // If using a new domain event model the conversion would happen here
          EventSeq.single(other.asInstanceOf[DomainEvent])
      }

    }
  }
  //#event-adapter

  //#command-handler
  val commandHandler: (State, Command) => Effect[DomainEvent, State] = (state, command) => {
    println(s"Command Handler $state $command")
    state match {
      case LookingAround(cart) =>
        command match {
          case AddItem(item) =>
            Effect.persist(ItemAdded(item))
          case get: GetCurrentCart =>
            Effect.reply(get)(cart)
          case _ =>
            Effect.none
        }
      case Shopping(cart) =>
        // TODO timeout
        command match {
          case AddItem(item) =>
            Effect.persist(ItemAdded(item))
          case Buy =>
            Effect.persist(OrderExecuted)
          case Leave =>
            Effect.persist(OrderDiscarded).thenStop()
          case get: GetCurrentCart =>
            Effect.reply(get)(cart)
          case _ =>
            Effect.none
        }
      case Inactive(_) =>
        // TODO timeout
        command match {
          case AddItem(item) =>
            Effect.persist(ItemAdded(item))
          case _ =>
            Effect.none
        }
      case Paid(cart) =>
        command match {
          case Leave =>
            Effect.stop()
          case get: GetCurrentCart =>
            Effect.reply(get)(cart)
          case _ =>
            Effect.none
        }
    }
  }
  //#command-handler

  //#event-handler
  val eventHandler: (State, DomainEvent) => State = (state, event) => {
    state match {
      case la @ LookingAround(cart) =>
        event match {
          case ItemAdded(item) => Shopping(cart.addItem(item))
          case _               => la
        }
      case s @ Shopping(cart) =>
        event match {
          case ItemAdded(item) => Shopping(cart.addItem(item))
          case OrderExecuted   => Paid(cart)
          case OrderDiscarded  => state // will be stopped
          case _               => s
        }
      case i @ Inactive(cart) =>
        event match {
          case ItemAdded(item) => Shopping(cart.addItem(item))
          case _               => i
        }
      case Paid(_) => state // no events after paid
    }
  }
  //#event-handler

  private def behavior(pid: PersistenceId): EventSourcedBehavior[Command, DomainEvent, State] =
    EventSourcedBehavior[Command, DomainEvent, State](
      pid,
      LookingAround(EmptyShoppingCart),
      commandHandler,
      eventHandler).snapshotAdapter(persistentFSMSnapshotAdapter).eventAdapter(new PersistentFsmEventAdapter())

}

class PersistentFsmToTypedMigrationSpec extends WordSpec with ScalaFutures {

  import akka.persistence.fsm.PersistentFSMSpec._

  "PersistentFSM migration to Persistence Typed" must {
    "work when snapshot is not current" in {
      val untypedActorSystem = akka.actor.ActorSystem("UntypedSystem", PersistentFsmToTypedMigrationSpec.config)
      val shirt = Item("1", "Shirt", 59.99f)
      val shoes = Item("2", "Shoes", 89.99f)
      val coat = Item("3", "Coat", 119.99f)
      val pid = "no-snapshot"
      try {
        import akka.testkit.TestProbe
        val reportActorProbe = TestProbe()(untypedActorSystem)
        val untypedProbe = TestProbe()(untypedActorSystem)
        implicit val untypedRef = untypedProbe.ref
        val fsmRef = untypedActorSystem.actorOf(WebStoreCustomerFSM.props(pid, reportActorProbe.ref))
        fsmRef ! AddItem(shirt)
        fsmRef ! AddItem(shoes)
        fsmRef.tell(GetCurrentCart, untypedProbe.ref)
        untypedProbe.expectMsg(NonEmptyShoppingCart(List(shirt, shoes)))

        untypedProbe.watch(fsmRef)
        fsmRef ! PoisonPill
        untypedProbe.expectTerminated(fsmRef)
      } finally {
        untypedActorSystem.terminate().futureValue
      }

      val typedTestKit = ActorTestKit("TypedSystem", PersistentFsmToTypedMigrationSpec.config)
      try {
        import typedTestKit._
        val typedProbe = akka.actor.testkit.typed.scaladsl.TestProbe[ShoppingCart]()
        val typedReplacement = spawn(ShoppingCartActor(PersistenceId(pid)))
        typedReplacement ! ShoppingCartActor.AddItem(coat)
        typedReplacement ! ShoppingCartActor.GetCurrentCart(typedProbe.ref)
        typedProbe.expectMessage(NonEmptyShoppingCart(List(shirt, shoes, coat)))
        typedReplacement ! ShoppingCartActor.Buy
        typedReplacement ! ShoppingCartActor.Leave
        typedProbe.expectTerminated(typedReplacement)
      } finally {
        typedTestKit.shutdownTestKit()
      }

    }

    "work if snapshot is current" in {
      val untypedActorSystem = akka.actor.ActorSystem("UntypedSystem", PersistentFsmToTypedMigrationSpec.config)
      val shirt = Item("1", "Shirt", 59.99f)
      val pid = "current-shapshot"
      try {
        import akka.testkit.TestProbe
        val reportActorProbe = TestProbe()(untypedActorSystem)
        val untypedProbe = TestProbe()(untypedActorSystem)
        implicit val untypedRef = untypedProbe.ref
        val fsmRef = untypedActorSystem.actorOf(WebStoreCustomerFSM.props(pid, reportActorProbe.ref))
        untypedProbe.watch(fsmRef)
        fsmRef ! AddItem(shirt)
        fsmRef.tell(GetCurrentCart, untypedProbe.ref)
        untypedProbe.expectMsg(NonEmptyShoppingCart(Seq(shirt)))
        fsmRef ! Buy
        fsmRef.tell(GetCurrentCart, untypedProbe.ref)
        untypedProbe.expectMsg(NonEmptyShoppingCart(Seq(shirt)))
      } finally {
        untypedActorSystem.terminate().futureValue
      }

      val typedTestKit = ActorTestKit("TypedSystem", PersistentFsmToTypedMigrationSpec.config)
      try {
        import typedTestKit._
        val typedProbe = akka.actor.testkit.typed.scaladsl.TestProbe[ShoppingCart]()
        val typedReplacement = spawn(ShoppingCartActor(PersistenceId(pid)))
        typedReplacement ! ShoppingCartActor.GetCurrentCart(typedProbe.ref)
        typedProbe.expectMessage(NonEmptyShoppingCart(Seq(shirt)))
      } finally {
        typedTestKit.shutdownTestKit()
      }
    }
  }

}
