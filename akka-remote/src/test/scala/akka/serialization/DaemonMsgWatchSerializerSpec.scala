/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.testkit.AkkaSpec
import akka.remote.DaemonMsgWatch
import akka.actor.Actor
import akka.actor.Props

object DaemonMsgWatchSerializerSpec {
  class MyActor extends Actor {
    def receive = {
      case _ ⇒
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DaemonMsgWatchSerializerSpec extends AkkaSpec {

  import DaemonMsgWatchSerializerSpec._

  val ser = SerializationExtension(system)

  "Serialization" must {

    "resolve DaemonMsgWatchSerializer" in {
      ser.serializerFor(classOf[DaemonMsgWatch]).getClass must be(classOf[DaemonMsgWatchSerializer])
    }

    "serialize and de-serialize DaemonMsgWatch" in {
      val watcher = system.actorOf(Props[MyActor], "watcher")
      val watched = system.actorOf(Props[MyActor], "watched")
      val msg = DaemonMsgWatch(watcher, watched)
      val bytes = ser.serialize(msg) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      ser.deserialize(bytes.asInstanceOf[Array[Byte]], classOf[DaemonMsgWatch]) match {
        case Left(exception) ⇒ fail(exception)
        case Right(m)        ⇒ assert(m === msg)
      }
    }

  }
}

