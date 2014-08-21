/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.Actor
import akka.actor.Props
import akka.pattern.pipe
import akka.stream.scaladsl.ImplicitFlowMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.testkit._

object ImplicitFlowMaterializerSpec {
  class SomeActor(input: List[String]) extends Actor with ImplicitFlowMaterializer {

    override def flowMaterializerSettings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")

    val flow = Flow(input).map(_.toUpperCase()).fold("")(_ + _)

    def receive = {
      case "run" ⇒
        // toFuture takes an implicit FlowMaterializer parameter, which is provided by ImplicitFlowMaterializer 
        val futureResult = flow.toFuture()
        import context.dispatcher
        futureResult pipeTo sender()
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ImplicitFlowMaterializerSpec extends AkkaSpec with ImplicitSender {
  import ImplicitFlowMaterializerSpec._

  "An ImplicitFlowMaterializer" must {

    "provide implicit FlowMaterializer" in {
      val actor = system.actorOf(Props(classOf[SomeActor], List("a", "b", "c")).withDispatcher("akka.test.stream-dispatcher"))
      actor ! "run"
      expectMsg("ABC")
    }
  }
}