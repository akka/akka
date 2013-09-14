package docs.persistence

import akka.actor.ActorSystem
import akka.persistence.{ Recover, Persistent, Processor }
import akka.testkit.{ ImplicitSender, AkkaSpec }

trait PersistenceDocSpec {
  val system: ActorSystem
  val config =
    """
      //#config
      akka.persistence.journal.leveldb.dir = "target/journal"
      //#config
    """

  import system._

  new AnyRef {
    //#definition
    import akka.persistence.{ Persistent, Processor }

    class MyProcessor extends Processor {
      def receive = {
        case Persistent(payload, sequenceNr) ⇒ // message has been written to journal
        case other                           ⇒ // message has not been written to journal
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
      override def preStartProcessor() = ()
      //#recover-on-start-disabled
      //#recover-on-restart-disabled
      override def preRestartProcessor(reason: Throwable, message: Option[Any]) = ()
      //#recover-on-restart-disabled
    }

    trait MyProcessor2 extends Processor {
      //#recover-on-start-custom
      override def preStartProcessor() {
        self ! Recover(toSequenceNr = 457L)
      }
      //#recover-on-start-custom
    }

    trait MyProcessor3 extends Processor {
      //#deletion
      override def preRestartProcessor(reason: Throwable, message: Option[Any]) {
        message match {
          case Some(p: Persistent) ⇒ delete(p)
          case _                   ⇒
        }
        super.preRestartProcessor(reason, message)
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
}
