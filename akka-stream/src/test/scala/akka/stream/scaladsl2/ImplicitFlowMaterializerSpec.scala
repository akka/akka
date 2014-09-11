/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.actor.{ Actor, Props }
import akka.pattern.pipe
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.testkit._

object ImplicitFlowMaterializerSpec {
  class SomeActor(input: List[String]) extends Actor with ImplicitFlowMaterializer {

    override def flowMaterializerSettings = MaterializerSettings(context.system)
      .withInputBuffer(initialSize = 2, maxSize = 16)
      .withFanOutBuffer(initialSize = 1, maxSize = 16)

    val flow = FlowFrom(input).map(_.toUpperCase())

    def receive = {
      case "run" ⇒
        // run takes an implicit FlowMaterializer parameter, which is provided by ImplicitFlowMaterializer
        import context.dispatcher
        val foldSink = FoldSink[String, String]("")(_ + _)
        val mf = flow.withSink(foldSink).run()
        foldSink.future(mf) pipeTo sender()
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