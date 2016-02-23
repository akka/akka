/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.contrib.mailbox

import com.typesafe.config.ConfigFactory

import akka.actor.{ Actor, ActorSystem, DeadLetter, PoisonPill, Props, actorRef2Scala }
import akka.testkit.{ AkkaSpec, EventFilter, ImplicitSender }

object PeekMailboxSpec {
  case object Check
  case object DoubleAck
  class PeekActor(tries: Int) extends Actor {
    var togo = tries
    def receive = {
      case Check ⇒
        sender() ! Check
        PeekMailboxExtension.ack()
      case DoubleAck ⇒
        PeekMailboxExtension.ack()
        PeekMailboxExtension.ack()
      case msg ⇒
        sender() ! msg
        if (togo == 0) throw new RuntimeException("DONTWANNA")
        togo -= 1
        PeekMailboxExtension.ack()
    }
    override def preRestart(cause: Throwable, msg: Option[Any]) {
      for (m ← msg if m == "DIE") context stop self // for testing the case of mailbox.cleanUp
    }
  }
}

class PeekMailboxSpec extends AkkaSpec("""
    peek-dispatcher {
      mailbox-type = "akka.contrib.mailbox.PeekMailboxType"
      max-retries = 2
    }
    """) with ImplicitSender {

  import PeekMailboxSpec._

  "A PeekMailbox" must {

    "retry messages" in {
      val a = system.actorOf(Props(classOf[PeekActor], 1).withDispatcher("peek-dispatcher"))
      a ! "hello"
      expectMsg("hello")
      EventFilter[RuntimeException]("DONTWANNA", occurrences = 1) intercept {
        a ! "world"
      }
      expectMsg("world")
      expectMsg("world")
      a ! Check
      expectMsg(Check)
    }

    "put a bound on retries" in {
      val a = system.actorOf(Props(classOf[PeekActor], 0).withDispatcher("peek-dispatcher"))
      EventFilter[RuntimeException]("DONTWANNA", occurrences = 3) intercept {
        a ! "hello"
      }
      a ! Check
      expectMsg("hello")
      expectMsg("hello")
      expectMsg("hello")
      expectMsg(Check)
    }

    "not waste messages on double-ack()" in {
      val a = system.actorOf(Props(classOf[PeekActor], 0).withDispatcher("peek-dispatcher"))
      a ! DoubleAck
      a ! Check
      expectMsg(Check)
    }

    "support cleanup" in {
      system.eventStream.subscribe(testActor, classOf[DeadLetter])
      val a = system.actorOf(Props(classOf[PeekActor], 0).withDispatcher("peek-dispatcher"))
      watch(a)
      EventFilter[RuntimeException]("DONTWANNA", occurrences = 1) intercept {
        a ! "DIE" // stays in the mailbox
      }
      expectMsg("DIE")
      expectMsgType[DeadLetter].message should ===("DIE")
      expectTerminated(a)
    }

  }

}

//#demo
class MyActor extends Actor {
  def receive = {
    case msg ⇒
      println(msg)
      doStuff(msg) // may fail
      PeekMailboxExtension.ack()
  }

  //#business-logic-elided
  var i = 0
  def doStuff(m: Any) {
    if (i == 1) throw new Exception("DONTWANNA")
    i += 1
  }

  override def postStop() {
    context.system.terminate()
  }
  //#business-logic-elided
}

object MyApp extends App {
  val system = ActorSystem("MySystem", ConfigFactory.parseString("""
    peek-dispatcher {
      mailbox-type = "akka.contrib.mailbox.PeekMailboxType"
      max-retries = 2
    }
    """))

  val myActor = system.actorOf(Props[MyActor].withDispatcher("peek-dispatcher"),
    name = "myActor")

  myActor ! "Hello"
  myActor ! "World"
  myActor ! PoisonPill
}
//#demo
