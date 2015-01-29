/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ Actor, Props }
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.testkit._
import akka.pattern.pipe

object ImplicitFlowMaterializerSpec {
  class SomeActor(input: List[String]) extends Actor with ImplicitFlowMaterializer {

    override def flowMaterializerSettings = ActorFlowMaterializerSettings(context.system)
      .withInputBuffer(initialSize = 2, maxSize = 16)

    val flow = Source(input).map(_.toUpperCase())

    def receive = {
      case "run" ⇒
        // run takes an implicit ActorFlowMaterializer parameter, which is provided by ImplicitFlowMaterializer
        import context.dispatcher
        flow.runFold("")(_ + _) pipeTo sender()
    }
  }
}

class ImplicitFlowMaterializerSpec extends AkkaSpec with ImplicitSender {
  import ImplicitFlowMaterializerSpec._

  "An ImplicitFlowMaterializer" must {

    "provide implicit ActorFlowMaterializer" in {
      val actor = system.actorOf(Props(classOf[SomeActor], List("a", "b", "c")).withDispatcher("akka.test.stream-dispatcher"))
      actor ! "run"
      expectMsg("ABC")
    }
  }
}
