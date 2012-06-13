/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.testkit

//#plain-spec
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit.ImplicitSender

object MySpec {
  class EchoActor extends Actor {
    def receive = {
      case x ⇒ sender ! x
    }
  }
}

//#implicit-sender
class MySpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpec with MustMatchers with BeforeAndAfterAll {
  //#implicit-sender

  def this() = this(ActorSystem("MySpec"))

  import MySpec._

  override def afterAll {
    system.shutdown()
  }

  "An Echo actor" must {

    "send back messages unchanged" in {
      val echo = system.actorOf(Props[EchoActor])
      echo ! "hello world"
      expectMsg("hello world")
    }

  }
}
//#plain-spec