/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.testkit.ImplicitSender
import akka.remote.testconductor.Controller.NodeInfo
import akka.actor.AddressFromURIString

object ControllerSpec {
  val config = """
    akka.testconductor.barrier-timeout = 5s
    akka.actor.provider = akka.remote.RemoteActorRefProvider
    akka.remote.netty.port = 0
    akka.actor.debug.fsm = on
    akka.actor.debug.lifecycle = on
    """
}

class ControllerSpec extends AkkaSpec(ControllerSpec.config) with ImplicitSender {

  "A Controller" must {

    "publish its nodes" in {
      val c = system.actorOf(Props(new Controller(1)))
      c ! NodeInfo("a", AddressFromURIString("akka://sys"), testActor)
      expectMsg(Send(Done))
      c ! NodeInfo("b", AddressFromURIString("akka://sys"), testActor)
      expectMsg(Send(Done))
      c ! Controller.GetNodes
      expectMsgType[Iterable[String]].toSet must be(Set("a", "b"))
    }

  }

}