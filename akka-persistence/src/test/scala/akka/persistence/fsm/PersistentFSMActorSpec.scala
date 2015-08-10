/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.fsm

import akka.actor._
import akka.persistence.PersistenceSpec
import akka.persistence.fsm.FSM.{ CurrentState, SubscribeTransitionCallBack, Transition }
import akka.persistence.fsm.PersistentFsmActor.FSMState
import akka.testkit._
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
abstract class PersistentFSMActorSpec(config: Config) extends PersistenceSpec(config) with ImplicitSender {
  import PersistentFSMActorSpec._

  //Dummy report actor, for tests that don't need it
  val dummyReportActorRef = TestProbe().ref

  "Persistent FSM Actor" must {
    "function as a regular FSM " in {
      val persistenceId = name
      val fsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))

      watch(fsmRef)
      fsmRef ! SubscribeTransitionCallBack(testActor)

      val shirt = Item("1", "Shirt", 59.99F)
      val shoes = Item("2", "Shoes", 89.99F)
      val coat = Item("3", "Coat", 119.99F)

      fsmRef ! GetCurrentCart
      fsmRef ! AddItem(shirt)
      fsmRef ! GetCurrentCart
      fsmRef ! AddItem(shoes)
      fsmRef ! GetCurrentCart
      fsmRef ! AddItem(coat)
      fsmRef ! GetCurrentCart
      fsmRef ! Buy
      fsmRef ! GetCurrentCart
      fsmRef ! Leave

      expectMsg(CurrentState(fsmRef, LookingAround))
      expectMsg(EmptyShoppingCart)

      expectMsg(Transition(fsmRef, LookingAround, Shopping))
      expectMsg(NonEmptyShoppingCart(List(shirt)))
      expectMsg(NonEmptyShoppingCart(List(shirt, shoes)))
      expectMsg(NonEmptyShoppingCart(List(shirt, shoes, coat)))

      expectMsg(Transition(fsmRef, Shopping, Paid))
      expectMsg(NonEmptyShoppingCart(List(shirt, shoes, coat)))

      expectTerminated(fsmRef)
    }

    "function as a regular FSM on state timeout" taggedAs TimingTest in {
      val persistenceId = name
      val fsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))

      watch(fsmRef)
      fsmRef ! SubscribeTransitionCallBack(testActor)

      val shirt = Item("1", "Shirt", 59.99F)

      fsmRef ! AddItem(shirt)

      expectMsg(CurrentState(fsmRef, LookingAround))
      expectMsg(Transition(fsmRef, LookingAround, Shopping))

      within(0.9 seconds, 1.9 seconds) {
        expectMsg(Transition(fsmRef, Shopping, Inactive))
      }

      within(1.9 seconds, 2.9 seconds) {
        expectTerminated(fsmRef)
      }
    }

    "recover successfully with correct state data" in {
      val persistenceId = name

      val fsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))
      watch(fsmRef)
      fsmRef ! SubscribeTransitionCallBack(testActor)

      val shirt = Item("1", "Shirt", 59.99F)
      val shoes = Item("2", "Shoes", 89.99F)
      val coat = Item("3", "Coat", 119.99F)

      fsmRef ! GetCurrentCart
      fsmRef ! AddItem(shirt)
      fsmRef ! GetCurrentCart
      fsmRef ! AddItem(shoes)
      fsmRef ! GetCurrentCart

      expectMsg(CurrentState(fsmRef, LookingAround))
      expectMsg(EmptyShoppingCart)

      expectMsg(Transition(fsmRef, LookingAround, Shopping))
      expectMsg(NonEmptyShoppingCart(List(shirt)))
      expectMsg(NonEmptyShoppingCart(List(shirt, shoes)))

      fsmRef ! PoisonPill
      expectTerminated(fsmRef)

      val recoveredFsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))
      watch(recoveredFsmRef)
      recoveredFsmRef ! SubscribeTransitionCallBack(testActor)

      recoveredFsmRef ! GetCurrentCart

      recoveredFsmRef ! AddItem(coat)
      recoveredFsmRef ! GetCurrentCart

      recoveredFsmRef ! Buy
      recoveredFsmRef ! GetCurrentCart
      recoveredFsmRef ! Leave

      expectMsg(CurrentState(recoveredFsmRef, Shopping))
      expectMsg(NonEmptyShoppingCart(List(shirt, shoes)))

      expectMsg(NonEmptyShoppingCart(List(shirt, shoes, coat)))

      expectMsg(Transition(recoveredFsmRef, Shopping, Paid))
      expectMsg(NonEmptyShoppingCart(List(shirt, shoes, coat)))

      expectTerminated(recoveredFsmRef)
    }

    "execute the defined actions following successful persistence of state change" in {
      val persistenceId = name

      val reportActorProbe = TestProbe()
      val fsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, reportActorProbe.ref))
      watch(fsmRef)
      fsmRef ! SubscribeTransitionCallBack(testActor)

      val shirt = Item("1", "Shirt", 59.99F)
      val shoes = Item("2", "Shoes", 89.99F)
      val coat = Item("3", "Coat", 119.99F)

      fsmRef ! AddItem(shirt)
      fsmRef ! AddItem(shoes)
      fsmRef ! AddItem(coat)
      fsmRef ! Buy
      fsmRef ! Leave

      expectMsg(CurrentState(fsmRef, LookingAround))
      expectMsg(Transition(fsmRef, LookingAround, Shopping))
      expectMsg(Transition(fsmRef, Shopping, Paid))
      reportActorProbe.expectMsg(PurchaseWasMade(List(shirt, shoes, coat)))
      expectTerminated(fsmRef)
    }

    "execute the defined actions following successful persistence of FSM stop" in {
      val persistenceId = name

      val reportActorProbe = TestProbe()
      val fsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, reportActorProbe.ref))
      watch(fsmRef)
      fsmRef ! SubscribeTransitionCallBack(testActor)

      val shirt = Item("1", "Shirt", 59.99F)
      val shoes = Item("2", "Shoes", 89.99F)
      val coat = Item("3", "Coat", 119.99F)

      fsmRef ! AddItem(shirt)
      fsmRef ! AddItem(shoes)
      fsmRef ! AddItem(coat)
      fsmRef ! Leave

      expectMsg(CurrentState(fsmRef, LookingAround))
      expectMsg(Transition(fsmRef, LookingAround, Shopping))
      reportActorProbe.expectMsg(ShoppingCardDiscarded)
      expectTerminated(fsmRef)
    }

    "recover successfully with correct state timeout" taggedAs TimingTest in {
      val persistenceId = name
      val fsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))

      watch(fsmRef)
      fsmRef ! SubscribeTransitionCallBack(testActor)

      val shirt = Item("1", "Shirt", 59.99F)

      fsmRef ! AddItem(shirt)

      expectMsg(CurrentState(fsmRef, LookingAround))
      expectMsg(Transition(fsmRef, LookingAround, Shopping))

      expectNoMsg(0.6 seconds) // arbitrarily chosen delay, less than the timeout, before stopping the FSM
      fsmRef ! PoisonPill
      expectTerminated(fsmRef)

      var recoveredFsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))
      watch(recoveredFsmRef)
      recoveredFsmRef ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(recoveredFsmRef, Shopping, Some(1 second)))

      within(0.9 seconds, 1.9 seconds) {
        expectMsg(Transition(recoveredFsmRef, Shopping, Inactive))
      }

      expectNoMsg(0.6 seconds) // arbitrarily chosen delay, less than the timeout, before stopping the FSM
      recoveredFsmRef ! PoisonPill
      expectTerminated(recoveredFsmRef)

      recoveredFsmRef = system.actorOf(WebStoreCustomerFSMActor.props(persistenceId, dummyReportActorRef))
      watch(recoveredFsmRef)
      recoveredFsmRef ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(recoveredFsmRef, Inactive, Some(2 seconds)))
      expectTerminated(recoveredFsmRef)
    }

    "not trigger onTransition for stay()" taggedAs TimingTest in {
      val persistenceId = name
      val probe = TestProbe()
      val fsmRef = system.actorOf(SimpleTransitionFSMActor.props(persistenceId, probe.ref))

      probe.expectMsg(3.seconds, "LookingAround -> LookingAround") // caused by initialize(), OK

      fsmRef ! "goto(the same state)" // causes goto()
      probe.expectMsg(3.seconds, "LookingAround -> LookingAround")

      fsmRef ! "stay" // causes stay()
      probe.expectNoMsg(3.seconds)
    }
  }

}

object PersistentFSMActorSpec {
  //#customer-states
  sealed trait UserState extends FSMState
  case object LookingAround extends UserState {
    override def identifier: String = "Looking Around"
  }
  case object Shopping extends UserState {
    override def identifier: String = "Shopping"
  }
  case object Inactive extends UserState {
    override def identifier: String = "Inactive"
  }
  case object Paid extends UserState {
    override def identifier: String = "Paid"
  }
  //#customer-states

  //#customer-states-data
  case class Item(id: String, name: String, price: Float)

  sealed trait ShoppingCart {
    def addItem(item: Item): ShoppingCart
    def empty(): ShoppingCart
  }
  case object EmptyShoppingCart extends ShoppingCart {
    def addItem(item: Item) = NonEmptyShoppingCart(item :: Nil)
    def empty() = this
  }
  case class NonEmptyShoppingCart(items: Seq[Item]) extends ShoppingCart {
    def addItem(item: Item) = NonEmptyShoppingCart(items :+ item)
    def empty() = EmptyShoppingCart
  }
  //#customer-states-data

  //#customer-commands
  sealed trait Command
  case class AddItem(item: Item) extends Command
  case object Buy extends Command
  case object Leave extends Command
  case object GetCurrentCart extends Command
  //#customer-commands

  //#customer-domain-events
  sealed trait DomainEvent
  case class ItemAdded(item: Item) extends DomainEvent
  case object OrderExecuted extends DomainEvent
  case object OrderDiscarded extends DomainEvent
  //#customer-domain-events

  //Side effects - report events to be sent to some "Report Actor"
  sealed trait ReportEvent
  case class PurchaseWasMade(items: Seq[Item]) extends ReportEvent
  case object ShoppingCardDiscarded extends ReportEvent

  class SimpleTransitionFSMActor(_persistenceId: String, reportActor: ActorRef)(implicit val domainEventClassTag: ClassTag[DomainEvent]) extends PersistentFsmActor[UserState, ShoppingCart, DomainEvent] {
    override val persistenceId = _persistenceId

    startWith(LookingAround, EmptyShoppingCart)

    when(LookingAround) {
      case Event("stay", _) ⇒ stay
      case Event(e, _)      ⇒ goto(LookingAround)
    }

    onTransition {
      case (from, to) ⇒ reportActor ! s"$from -> $to"
    }

    override def applyEvent(domainEvent: DomainEvent, currentData: ShoppingCart): ShoppingCart =
      currentData
  }
  object SimpleTransitionFSMActor {
    def props(persistenceId: String, reportActor: ActorRef) =
      Props(new SimpleTransitionFSMActor(persistenceId, reportActor))
  }

  class WebStoreCustomerFSMActor(_persistenceId: String, reportActor: ActorRef)(implicit val domainEventClassTag: ClassTag[DomainEvent]) extends PersistentFsmActor[UserState, ShoppingCart, DomainEvent] {
    override def persistenceId = _persistenceId

    //#customer-fsm-body
    startWith(LookingAround, EmptyShoppingCart)

    when(LookingAround) {
      case Event(AddItem(item), _) ⇒
        goto(Shopping) applying ItemAdded(item) forMax (1 seconds)
      case Event(GetCurrentCart, data) ⇒
        stay replying data
    }

    when(Shopping) {
      case Event(AddItem(item), _) ⇒
        stay applying ItemAdded(item) forMax (1 seconds)
      case Event(Buy, _) ⇒
        goto(Paid) applying OrderExecuted andThen {
          case NonEmptyShoppingCart(items) ⇒ reportActor ! PurchaseWasMade(items)
          case EmptyShoppingCart           ⇒ // do nothing...
        }
      case Event(Leave, _) ⇒
        stop applying OrderDiscarded andThen {
          case _ ⇒ reportActor ! ShoppingCardDiscarded
        }
      case Event(GetCurrentCart, data) ⇒
        stay replying data
      case Event(StateTimeout, _) ⇒
        goto(Inactive) forMax (2 seconds)
    }

    when(Inactive) {
      case Event(AddItem(item), _) ⇒
        goto(Shopping) applying ItemAdded(item) forMax (1 seconds)
      case Event(StateTimeout, _) ⇒
        stop applying OrderDiscarded andThen {
          case _ ⇒ reportActor ! ShoppingCardDiscarded
        }
    }

    when(Paid) {
      case Event(Leave, _) ⇒ stop()
      case Event(GetCurrentCart, data) ⇒
        stay replying data
    }
    //#customer-fsm-body

    /**
     * Override this handler to define the action on Domain Event
     *
     * @param event domain event to apply
     * @param cartBeforeEvent state data of the previous state
     */
    //#customer-apply-event
    override def applyEvent(event: DomainEvent, cartBeforeEvent: ShoppingCart): ShoppingCart = {
      event match {
        case ItemAdded(item) ⇒ cartBeforeEvent.addItem(item)
        case OrderExecuted   ⇒ cartBeforeEvent
        case OrderDiscarded  ⇒ cartBeforeEvent.empty()
      }
    }
    //#customer-apply-event
  }

  object WebStoreCustomerFSMActor {
    def props(persistenceId: String, reportActor: ActorRef) =
      Props(new WebStoreCustomerFSMActor(persistenceId, reportActor))
  }
}

class LeveldbPersistentFSMActorSpec extends PersistentFSMActorSpec(PersistenceSpec.config("leveldb", "PersistentFSMActorSpec"))
class InmemPersistentFSMActorSpec extends PersistentFSMActorSpec(PersistenceSpec.config("inmem", "PersistentFSMActorSpec"))
