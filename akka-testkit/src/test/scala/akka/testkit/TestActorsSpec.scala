/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

class TestActorsSpec extends AkkaSpec with ImplicitSender {

  import TestActors.echoActorProps

  "A EchoActor" must {
    "send back messages unchanged" in {
      val message = "hello world"
      val echo = system.actorOf(echoActorProps)

      echo ! message

      expectMsg(message)
    }
  }
}
