/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import scala.concurrent.duration._
import akka.Done
import akka.event.Logging._
import akka.typed.ScalaDSL._
import akka.typed.AskPattern._
import com.typesafe.config.ConfigFactory
import java.util.concurrent.Executor
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

object EventStreamSpec {
  @volatile var logged = Vector.empty[LogEvent]

  class MyLogger extends Logger {
    def initialBehavior: Behavior[Logger.Command] =
      ContextAware { ctx ⇒
        Total {
          case Logger.Initialize(es, replyTo) ⇒
            replyTo ! ctx.watch(ctx.spawn(Static { (ev: LogEvent) ⇒ logged :+= ev }, "logger"))
            Empty
        }
      }
  }

  val config = ConfigFactory.parseString("""
akka.typed.loggers = ["akka.typed.internal.EventStreamSpec$MyLogger"]
""")

  // class hierarchy for subchannel test
  class A
  class B2 extends A
  class B3 extends A
  class C extends B2

  trait T
  trait AT extends T
  trait ATT extends AT
  trait BT extends T
  trait BTT extends BT
  class CC
  class CCATBT extends CC with ATT with BTT
}

class EventStreamSpec extends TypedSpec(EventStreamSpec.config) with Eventually {
  import EventStreamSpec._

  object `An EventStreamImpl` {

    val es = nativeSystem.eventStream
    val root = nativeSystem.path

    def `must work in full system`(): Unit = {
      es.logLevel should ===(WarningLevel)
      nativeSystem.log.error("hello world")
      nativeSystem.log.debug("should not see this")
      es.setLogLevel(DebugLevel)
      es.logLevel should ===(DebugLevel)
      nativeSystem.log.debug("hello world DEBUG")
      nativeSystem.log.info("hello world INFO")

      eventually(logged.map(_.message) should ===(Vector("hello world", "hello world DEBUG", "hello world INFO")))
      logged = Vector.empty
    }

    def `must manage subscribers`(): Unit = {
      val box = Inbox[AnyRef]("manage")
      val ref: ActorRef[String] = box.ref
      es.subscribe(ref, classOf[String]) should ===(true)
      es.publish("hello")
      es.unsubscribe(ref)
      es.publish("my")
      es.subscribe(ref, classOf[String]) should ===(true)
      es.publish("lovely")
      es.unsubscribe(ref, classOf[String]) should ===(true)
      es.publish("quaint")
      es.subscribe(ref, classOf[String]) should ===(true)
      es.publish("little")
      es.unsubscribe(box.ref, classOf[AnyRef]) should ===(true)
      es.publish("grey")
      es.subscribe(ref, classOf[String]) should ===(true)
      es.publish("world")
      box.receiveAll() should ===(Seq[AnyRef]("hello", "lovely", "little", "world"))
    }

    def `must care about types`(): Unit = {
      val ref = Inbox[String]("types").ref
      "es.subscribe(ref, classOf[AnyRef])" shouldNot typeCheck
      "es.unsubscribe(ref, classOf[AnyRef])" shouldNot typeCheck
    }

    def `must manage subchannels using classes`(): Unit = {
      val box = Inbox[A]("subchannelclass")
      val a = new A
      val b1 = new B2
      val b2 = new B3
      val c = new C
      es.subscribe(box.ref, classOf[B3]) should ===(true)
      es.publish(c)
      es.publish(b2)
      box.receiveMsg() should ===(b2)
      es.subscribe(box.ref, classOf[A]) should ===(true)
      es.publish(c)
      box.receiveMsg() should ===(c)
      es.publish(b1)
      box.receiveMsg() should ===(b1)
      es.unsubscribe(box.ref, classOf[B2]) should ===(true)
      es.publish(c)
      es.publish(b2)
      es.publish(a)
      box.receiveMsg() should ===(b2)
      box.receiveMsg() should ===(a)
      box.hasMessages should ===(false)
    }

    def `must manage sub-channels using classes and traits (update on subscribe)`(): Unit = {
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1 = Inbox[AT]("subAT")
      val a2 = Inbox[BT]("subBT")
      val a3 = Inbox[CC]("subCC")
      val a4 = Inbox[CCATBT]("subCCATBT")

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should ===(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.receiveMsg() should ===(tm2)
      a2.receiveMsg() should ===(tm2)
      a3.receiveMsg() should ===(tm1)
      a3.receiveMsg() should ===(tm2)
      a4.receiveMsg() should ===(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CC]) should ===(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should ===(true)
    }

    def `must manage sub-channels using classes and traits (update on unsubscribe)`(): Unit = {
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1 = Inbox[AT]("subAT")
      val a2 = Inbox[BT]("subBT")
      val a3 = Inbox[CC]("subCC")
      val a4 = Inbox[CCATBT]("subCCATBT")

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CC]) should ===(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.receiveMsg() should ===(tm2)
      a2.receiveMsg() should ===(tm2)
      a3.hasMessages should ===(false)
      a4.receiveMsg() should ===(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should ===(true)
    }

    def `must manage sub-channels using classes and traits (update on unsubscribe all)`(): Unit = {
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1 = Inbox[AT]("subAT")
      val a2 = Inbox[BT]("subBT")
      val a3 = Inbox[CC]("subCC")
      val a4 = Inbox[CCATBT]("subCCATBT")

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should ===(true)
      es.unsubscribe(a3.ref)
      es.publish(tm1)
      es.publish(tm2)
      a1.receiveMsg() should ===(tm2)
      a2.receiveMsg() should ===(tm2)
      a3.hasMessages should ===(false)
      a4.receiveMsg() should ===(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should ===(true)
    }

    def `must manage sub-channels using classes and traits (update on publish)`(): Unit = {
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1 = Inbox[AT]("subAT")
      val a2 = Inbox[BT]("subBT")

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.receiveMsg() should ===(tm2)
      a2.receiveMsg() should ===(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
    }

    def `must manage sub-channels using classes and traits (unsubscribe classes used with trait)`(): Unit = {
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1 = Inbox[AT]("subAT")
      val a2 = Inbox[AnyRef]("subBT")
      val a3 = Inbox[CC]("subCC")

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a2.ref, classOf[CC]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.unsubscribe(a2.ref, classOf[CC]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CCATBT]) should ===(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.receiveMsg() should ===(tm2)
      a2.receiveMsg() should ===(tm2)
      a3.receiveMsg() should ===(tm1)
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CC]) should ===(true)
    }

    def `must manage sub-channels using classes and traits (subscribe after publish)`(): Unit = {
      val tm1 = new CCATBT
      val a1 = Inbox[AT]("subAT")
      val a2 = Inbox[BTT]("subBTT")

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.publish(tm1)
      a1.receiveMsg() should ===(tm1)
      a2.hasMessages should ===(false)
      es.subscribe(a2.ref, classOf[BTT]) should ===(true)
      es.publish(tm1)
      a1.receiveMsg() should ===(tm1)
      a2.receiveMsg() should ===(tm1)
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BTT]) should ===(true)
    }

    def `must watch subscribers`(): Unit = {
      val ref = new DebugRef[String](root / "watch", true)
      es.subscribe(ref, classOf[String]) should ===(true)
      es.subscribe(ref, classOf[String]) should ===(false)
      eventually(ref.hasSignal should ===(true))
      val unsubscriber = ref.receiveSignal() match {
        case Watch(`ref`, watcher) ⇒ watcher
        case other                 ⇒ fail(s"expected Watch(), got $other")
      }
      ref.hasSomething should ===(false)
      unsubscriber.sorryForNothing.sendSystem(DeathWatchNotification(ref, null))
      eventually(es.subscribe(ref, classOf[String]) should ===(true))
    }

    def `must unsubscribe an actor upon termination`(): Unit = {
      val ref = nativeSystem ? TypedSpec.Create(Total[Done](d ⇒ Stopped), "tester") futureValue Timeout(1.second)
      es.subscribe(ref, classOf[Done]) should ===(true)
      es.subscribe(ref, classOf[Done]) should ===(false)
      ref ! Done
      eventually(es.subscribe(ref, classOf[Done]) should ===(true))
    }

    def `must unsubscribe the actor, when it subscribes already in terminated state`(): Unit = {
      val ref = nativeSystem ? TypedSpec.Create(Stopped[Done], "tester") futureValue Timeout(1.second)
      val wait = new DebugRef[Done](root / "wait", true)
      ref.sorry.sendSystem(Watch(ref, wait))
      eventually(wait.hasSignal should ===(true))
      wait.receiveSignal() should ===(DeathWatchNotification(ref, null))
      es.subscribe(ref, classOf[Done]) should ===(true)
      eventually(es.subscribe(ref, classOf[Done]) should ===(true))
    }

    def `must unwatch an actor from unsubscriber when that actor unsubscribes from the stream`(): Unit = {
      val ref = new DebugRef[String](root / "watch", true)
      es.subscribe(ref, classOf[String]) should ===(true)
      es.subscribe(ref, classOf[String]) should ===(false)
      eventually(ref.hasSignal should ===(true))
      val unsubscriber = ref.receiveSignal() match {
        case Watch(`ref`, watcher) ⇒ watcher
        case other                 ⇒ fail(s"expected Watch(), got $other")
      }
      ref.hasSomething should ===(false)
      es.unsubscribe(ref)
      eventually(ref.hasSignal should ===(true))
      ref.receiveSignal() should ===(Unwatch(ref, unsubscriber))
    }

    def `must unwatch an actor from unsubscriber when that actor unsubscribes from channels it subscribed`(): Unit = {
      val ref = new DebugRef[AnyRef](root / "watch", true)
      es.subscribe(ref, classOf[String]) should ===(true)
      es.subscribe(ref, classOf[String]) should ===(false)
      es.subscribe(ref, classOf[Integer]) should ===(true)
      es.subscribe(ref, classOf[Integer]) should ===(false)
      eventually(ref.hasSignal should ===(true))
      val unsubscriber = ref.receiveSignal() match {
        case Watch(`ref`, watcher) ⇒ watcher
        case other                 ⇒ fail(s"expected Watch(), got $other")
      }
      ref.hasSomething should ===(false)
      es.unsubscribe(ref, classOf[Integer])
      Thread.sleep(50)
      ref.hasSomething should ===(false)
      es.unsubscribe(ref, classOf[String])
      eventually(ref.hasSignal should ===(true))
      ref.receiveSignal() should ===(Unwatch(ref, unsubscriber))
    }

  }

}
