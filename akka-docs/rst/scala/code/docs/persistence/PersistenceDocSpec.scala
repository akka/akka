/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence

import akka.actor.{ Actor, ActorSystem, Props }
import akka.persistence._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps
trait PersistenceDocSpec {
  val config =
    """
      //#auto-update-interval
      akka.persistence.view.auto-update-interval = 5s
      //#auto-update-interval
      //#auto-update
      akka.persistence.view.auto-update = off
      //#auto-update
    """

  trait SomeOtherMessage

  implicit val system: ActorSystem

  import system._

  new AnyRef {
    //#definition
    import akka.persistence.{ PersistenceFailure, Persistent, Processor }

    class MyProcessor extends Processor {
      def receive = {
        case Persistent(payload, sequenceNr)                =>
        // message successfully written to journal
        case PersistenceFailure(payload, sequenceNr, cause) =>
        // message failed to be written to journal
        case m: SomeOtherMessage                            =>
        // message not written to journal
      }
    }
    //#definition

    //#usage
    import akka.actor.Props

    val processor = actorOf(Props[MyProcessor], name = "myProcessor")

    processor ! Persistent("foo") // will be journaled
    processor ! "bar" // will not be journaled
    //#usage

    //#recover-explicit
    processor ! Recover()
    //#recover-explicit
  }

  new AnyRef {
    trait MyProcessor1 extends PersistentActor {
      //#recover-on-start-disabled
      override def preStart() = ()
      //#recover-on-start-disabled
      //#recover-on-restart-disabled
      override def preRestart(reason: Throwable, message: Option[Any]) = ()
      //#recover-on-restart-disabled
    }

    trait MyProcessor2 extends PersistentActor {
      //#recover-on-start-custom
      override def preStart() {
        self ! Recover(toSequenceNr = 457L)
      }
      //#recover-on-start-custom
    }

    trait MyProcessor3 extends PersistentActor {
      //#deletion
      override def preRestart(reason: Throwable, message: Option[Any]) {
        message match {
          case Some(p: Persistent) => deleteMessage(p.sequenceNr)
          case _                   =>
        }
        super.preRestart(reason, message)
      }
      //#deletion
    }

    class MyProcessor4 extends PersistentActor {
      override def persistenceId = "my-stable-persistence-id"

      //#recovery-completed

      def receiveRecover: Receive = {
        case RecoveryCompleted => recoveryCompleted()
        case evt               => //...
      }

      def receiveCommand: Receive = {
        case msg => //...
      }

      def recoveryCompleted(): Unit = {
        // perform init after recovery, before any other messages
        // ...
      }

      //#recovery-completed
    }
  }

  new AnyRef {
    trait ProcessorMethods {
      //#persistence-id
      def persistenceId: String
      //#persistence-id
      //#recovery-status
      def recoveryRunning: Boolean
      def recoveryFinished: Boolean
      //#recovery-status
      //#current-message
      implicit def currentPersistentMessage: Option[Persistent]
      //#current-message
    }
    class MyProcessor1 extends Processor with ProcessorMethods {
      //#persistence-id-override
      override def persistenceId = "my-stable-persistence-id"
      //#persistence-id-override
      def receive = {
        case _ =>
      }
    }
  }

  new AnyRef {
    //#at-least-once-example
    import akka.actor.{ Actor, ActorPath }
    import akka.persistence.AtLeastOnceDelivery

    case class Msg(deliveryId: Long, s: String)
    case class Confirm(deliveryId: Long)

    sealed trait Evt
    case class MsgSent(s: String) extends Evt
    case class MsgConfirmed(deliveryId: Long) extends Evt

    class MyPersistentActor(destination: ActorPath)
      extends PersistentActor with AtLeastOnceDelivery {

      def receiveCommand: Receive = {
        case s: String           => persist(MsgSent(s))(updateState)
        case Confirm(deliveryId) => persist(MsgConfirmed(deliveryId))(updateState)
      }

      def receiveRecover: Receive = {
        case evt: Evt => updateState(evt)
      }

      def updateState(evt: Evt): Unit = evt match {
        case MsgSent(s) =>
          deliver(destination, deliveryId => Msg(deliveryId, s))

        case MsgConfirmed(deliveryId) => confirmDelivery(deliveryId)
      }
    }

    class MyDestination extends Actor {
      def receive = {
        case Msg(deliveryId, s) =>
          // ...
          sender() ! Confirm(deliveryId)
      }
    }
    //#at-least-once-example
  }

  new AnyRef {
    //#channel-example
    import akka.actor.{ Actor, Props }
    import akka.persistence.{ Channel, Deliver, Persistent, Processor }

    class MyProcessor extends Processor {
      val destination = context.actorOf(Props[MyDestination])
      val channel = context.actorOf(Channel.props(), name = "myChannel")

      def receive = {
        case p @ Persistent(payload, _) =>
          channel ! Deliver(p.withPayload(s"processed ${payload}"), destination.path)
      }
    }

    class MyDestination extends Actor {
      def receive = {
        case p @ ConfirmablePersistent(payload, sequenceNr, redeliveries) =>
          // ...
          p.confirm()
      }
    }
    //#channel-example

    class MyProcessor2 extends Processor {
      val destination = context.actorOf(Props[MyDestination])
      val channel =
        //#channel-id-override
        context.actorOf(Channel.props("my-stable-channel-id"))
      //#channel-id-override

      //#channel-custom-settings
      context.actorOf(Channel.props(
        ChannelSettings(redeliverInterval = 30 seconds, redeliverMax = 15)),
        name = "myChannel")
      //#channel-custom-settings

      def receive = {
        case p @ Persistent(payload, _) =>
          //#channel-example-reply
          channel ! Deliver(p.withPayload(s"processed ${payload}"), sender.path)
        //#channel-example-reply
      }

      //#channel-custom-listener
      class MyListener extends Actor {
        def receive = {
          case RedeliverFailure(messages) => // ...
        }
      }

      val myListener = context.actorOf(Props[MyListener])
      val myChannel = context.actorOf(Channel.props(
        ChannelSettings(redeliverFailureListener = Some(myListener))))
      //#channel-custom-listener
    }

    class MyProcessor3 extends Processor {
      def receive = {
        //#payload-pattern-matching
        case Persistent(payload, _) =>
        //#payload-pattern-matching
      }
    }

    class MyProcessor4 extends Processor {
      def receive = {
        //#sequence-nr-pattern-matching
        case Persistent(_, sequenceNr) =>
        //#sequence-nr-pattern-matching
      }
    }
  }

  new AnyRef {
    //#fsm-example
    import akka.actor.FSM
    import akka.persistence.{ Persistent, Processor }

    class PersistentDoor extends Processor with FSM[String, Int] {
      startWith("closed", 0)

      when("closed") {
        case Event(Persistent("open", _), counter) =>
          goto("open") using (counter + 1) replying (counter)
      }

      when("open") {
        case Event(Persistent("close", _), counter) =>
          goto("closed") using (counter + 1) replying (counter)
      }
    }
    //#fsm-example
  }

  new AnyRef {
    //#save-snapshot
    class MyProcessor extends Processor {
      var state: Any = _

      def receive = {
        case "snap"                                => saveSnapshot(state)
        case SaveSnapshotSuccess(metadata)         => // ...
        case SaveSnapshotFailure(metadata, reason) => // ...
      }
    }
    //#save-snapshot
  }

  new AnyRef {
    //#snapshot-offer
    class MyProcessor extends Processor {
      var state: Any = _

      def receive = {
        case SnapshotOffer(metadata, offeredSnapshot) => state = offeredSnapshot
        case Persistent(payload, sequenceNr)          => // ...
      }
    }
    //#snapshot-offer

    import akka.actor.Props

    val processor = system.actorOf(Props[MyProcessor])

    //#snapshot-criteria
    processor ! Recover(fromSnapshot = SnapshotSelectionCriteria(
      maxSequenceNr = 457L,
      maxTimestamp = System.currentTimeMillis))
    //#snapshot-criteria
  }

  new AnyRef {
    import akka.actor.Props
    //#batch-write
    class MyProcessor extends Processor {
      def receive = {
        case Persistent("a", _) => // ...
        case Persistent("b", _) => // ...
      }
    }

    val system = ActorSystem("example")
    val processor = system.actorOf(Props[MyProcessor])

    processor ! PersistentBatch(List(Persistent("a"), Persistent("b")))
    //#batch-write
    system.shutdown()
  }

  new AnyRef {
    import akka.actor._
    trait MyActor extends Actor {
      val destination: ActorRef = null
      //#persistent-channel-example
      val channel = context.actorOf(PersistentChannel.props(
        PersistentChannelSettings(redeliverInterval = 30 seconds, redeliverMax = 15)),
        name = "myPersistentChannel")

      channel ! Deliver(Persistent("example"), destination.path)
      //#persistent-channel-example
      //#persistent-channel-watermarks
      PersistentChannelSettings(
        pendingConfirmationsMax = 10000,
        pendingConfirmationsMin = 2000)
      //#persistent-channel-watermarks
      //#persistent-channel-reply
      PersistentChannelSettings(replyPersistent = true)
      //#persistent-channel-reply
    }
  }

  new AnyRef {
    import akka.actor.ActorRef

    //#reliable-event-delivery
    class MyPersistentActor(destination: ActorRef) extends PersistentActor {
      val channel = context.actorOf(Channel.props("channel"))

      override def persistenceId = "my-stable-persistence-id"

      def handleEvent(event: String) = {
        // update state
        // ...
        // reliably deliver events
        channel ! Deliver(Persistent(event), destination.path)
      }

      def receiveRecover: Receive = {
        case event: String => handleEvent(event)
      }

      def receiveCommand: Receive = {
        case "cmd" => {
          // ...
          persist("evt")(handleEvent)
        }
      }
    }
    //#reliable-event-delivery
  }

  new AnyRef {

    val processor = system.actorOf(Props[MyPersistentActor]())

    //#persist-async
    class MyPersistentActor extends PersistentActor {

      override def persistenceId = "my-stable-persistence-id"

      def receiveRecover: Receive = {
        case _ => // handle recovery here
      }

      def receiveCommand: Receive = {
        case c: String => {
          sender() ! c
          persistAsync(s"evt-$c-1") { e => sender() ! e }
          persistAsync(s"evt-$c-2") { e => sender() ! e }
        }
      }
    }

    // usage
    processor ! "a"
    processor ! "b"

    // possible order of received messages:
    // a
    // b
    // evt-a-1
    // evt-a-2
    // evt-b-1
    // evt-b-2

    //#persist-async
  }
  new AnyRef {

    val processor = system.actorOf(Props[MyPersistentActor]())

    //#defer
    class MyPersistentActor extends PersistentActor {

      override def persistenceId = "my-stable-persistence-id"

      def receiveRecover: Receive = {
        case _ => // handle recovery here
      }

      def receiveCommand: Receive = {
        case c: String => {
          sender() ! c
          persistAsync(s"evt-$c-1") { e => sender() ! e }
          persistAsync(s"evt-$c-2") { e => sender() ! e }
          defer(s"evt-$c-3") { e => sender() ! e }
        }
      }
    }
    //#defer

    //#defer-caller
    processor ! "a"
    processor ! "b"

    // order of received messages:
    // a
    // b
    // evt-a-1
    // evt-a-2
    // evt-a-3
    // evt-b-1
    // evt-b-2
    // evt-b-3

    //#defer-caller
  }
  new AnyRef {
    import akka.actor.Props

    //#view
    class MyView extends PersistentView {
      override def persistenceId: String = "some-persistence-id"
      override def viewId: String = "some-persistence-id-view"

      def receive: Actor.Receive = {
        case payload if isPersistent =>
        // handle message from journal...
        case payload                 =>
        // handle message from user-land...
      }
    }
    //#view

    //#view-update
    val view = system.actorOf(Props[MyView])
    view ! Update(await = true)
    //#view-update
  }

}
