/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.{ Actor, Props }
import akka.pattern.pipe
import akka.stream.scaladsl.{ Flow, ImplicitFlowMaterializer }
import akka.stream.testkit.AkkaSpec
import akka.testkit._

object ImplicitFlowMaterializerSpec {
  class SomeActor(input: List[String]) extends Actor with ImplicitFlowMaterializer {

    override def flowMaterializerSettings = MaterializerSettings(context.system)
      .withInputBuffer(initialSize = 2, maxSize = 16)
      .withFanOutBuffer(initialSize = 1, maxSize = 16)

    val flow = Flow(input).map(_.toUpperCase()).fold("")(_ + _)

    def receive = {
      case "run" ⇒
        // toFuture takes an implicit FlowMaterializer parameter, which is provided by ImplicitFlowMaterializer
        import context.dispatcher
        val futureResult = flow.toFuture()
        futureResult pipeTo sender()
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ImplicitFlowMaterializerSpec extends AkkaSpec with ImplicitSender {
  import akka.stream.ImplicitFlowMaterializerSpec._

  "An ImplicitFlowMaterializer" must {

    "provide implicit FlowMaterializer" in {
      val actor = system.actorOf(Props(classOf[SomeActor], List("a", "b", "c")).withDispatcher("akka.test.stream-dispatcher"))
      actor ! "run"
      expectMsg("ABC")
    }
  }
}