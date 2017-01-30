/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.testkit

//#plain-spec
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestActors }
import akka.testkit.scaladsl.TestKit
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

//#implicit-sender
class MySpec() extends TestKit(ActorSystem("MySpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  //#implicit-sender

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An Echo actor" must {

    "send back messages unchanged" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      echo ! "hello world"
      expectMsg("hello world")
    }

  }
}
//#plain-spec
