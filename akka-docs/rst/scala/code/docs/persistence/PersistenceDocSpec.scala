package docs.persistence

import akka.actor.ActorSystem
import akka.persistence._

trait PersistenceDocSpec {
  val system: ActorSystem

  import system._

  new AnyRef {
    //#definition
    import akka.persistence.{ Persistent, PersistenceFailure, Processor }

    class MyProcessor extends Processor {
      def receive = {
        case Persistent(payload, sequenceNr) ⇒ {
          // message successfully written to journal
        }
        case PersistenceFailure(payload, sequenceNr, cause) ⇒ {
          // message failed to be written to journal
        }
        case other ⇒ {
          // message not written to journal
        }
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
    trait MyProcessor1 extends Processor {
      //#recover-on-start-disabled
      override def preStart() = ()
      //#recover-on-start-disabled
      //#recover-on-restart-disabled
      override def preRestart(reason: Throwable, message: Option[Any]) = ()
      //#recover-on-restart-disabled
    }

    trait MyProcessor2 extends Processor {
      //#recover-on-start-custom
      override def preStart() {
        self ! Recover(toSequenceNr = 457L)
      }
      //#recover-on-start-custom
    }

    trait MyProcessor3 extends Processor {
      //#deletion
      override def preRestart(reason: Throwable, message: Option[Any]) {
        message match {
          case Some(p: Persistent) ⇒ deleteMessage(p)
          case _                   ⇒
        }
        super.preRestart(reason, message)
      }
      //#deletion
    }
  }

  new AnyRef {
    trait ProcessorMethods {
      //#processor-id
      def processorId: String
      //#processor-id
      //#recovery-status
      def recoveryRunning: Boolean
      def recoveryFinished: Boolean
      //#recovery-status
      //#current-message
      implicit def currentPersistentMessage: Option[Persistent]
      //#current-message
    }
    class MyProcessor1 extends Processor with ProcessorMethods {
      //#processor-id-override
      override def processorId = "my-stable-processor-id"
      //#processor-id-override
      def receive = {
        case _ ⇒
      }
    }
  }

  new AnyRef {
    //#channel-example
    import akka.actor.{ Actor, Props }
    import akka.persistence.{ Channel, Deliver, Persistent, Processor }

    class MyProcessor extends Processor {
      val destination = context.actorOf(Props[MyDestination])
      val channel = context.actorOf(Channel.props(), name = "myChannel")

      def receive = {
        case p @ Persistent(payload, _) ⇒ {
          channel ! Deliver(p.withPayload(s"processed ${payload}"), destination)
        }
      }
    }

    class MyDestination extends Actor {
      def receive = {
        case p @ Persistent(payload, _) ⇒ {
          println(s"received ${payload}")
          p.confirm()
        }
      }
    }
    //#channel-example

    class MyProcessor2 extends Processor {
      import akka.persistence.Resolve

      val destination = context.actorOf(Props[MyDestination])
      val channel =
        //#channel-id-override
        context.actorOf(Channel.props("my-stable-channel-id"))
      //#channel-id-override

      def receive = {
        case p @ Persistent(payload, _) ⇒ {
          //#channel-example-reply
          channel ! Deliver(p.withPayload(s"processed ${payload}"), sender)
          //#channel-example-reply
          //#resolve-destination
          channel ! Deliver(p, sender, Resolve.Destination)
          //#resolve-destination
          //#resolve-sender
          channel forward Deliver(p, destination, Resolve.Sender)
          //#resolve-sender
        }
      }
    }

    class MyProcessor3 extends Processor {
      def receive = {
        //#payload-pattern-matching
        case Persistent(payload, _) ⇒
        //#payload-pattern-matching
      }
    }

    class MyProcessor4 extends Processor {
      def receive = {
        //#sequence-nr-pattern-matching
        case Persistent(_, sequenceNr) ⇒
        //#sequence-nr-pattern-matching
      }
    }
  }

  new AnyRef {
    //#fsm-example
    import akka.actor.FSM
    import akka.persistence.{ Processor, Persistent }

    class PersistentDoor extends Processor with FSM[String, Int] {
      startWith("closed", 0)

      when("closed") {
        case Event(Persistent("open", _), counter) ⇒ {
          goto("open") using (counter + 1) replying (counter)
        }
      }

      when("open") {
        case Event(Persistent("close", _), counter) ⇒ {
          goto("closed") using (counter + 1) replying (counter)
        }
      }
    }
    //#fsm-example
  }

  new AnyRef {
    //#save-snapshot
    class MyProcessor extends Processor {
      var state: Any = _

      def receive = {
        case "snap"                                ⇒ saveSnapshot(state)
        case SaveSnapshotSuccess(metadata)         ⇒ // ...
        case SaveSnapshotFailure(metadata, reason) ⇒ // ...
      }
    }
    //#save-snapshot
  }

  new AnyRef {
    //#snapshot-offer
    class MyProcessor extends Processor {
      var state: Any = _

      def receive = {
        case SnapshotOffer(metadata, offeredSnapshot) ⇒ state = offeredSnapshot
        case Persistent(payload, sequenceNr)          ⇒ // ...
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
}
