/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ Actor, Props }
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.testkit._
import akka.pattern.pipe

object ImplicitMaterializerSpec {
  class SomeActor(input: List[String]) extends Actor with ImplicitMaterializer {

    override def materializerSettings = ActorMaterializerSettings(context.system)
      .withInputBuffer(initialSize = 2, maxSize = 16)

    val flow = Source(input).map(_.toUpperCase())

    def receive = {
      case "run" ⇒
        // run takes an implicit ActorMaterializer parameter, which is provided by ImplicitMaterializer
        import context.dispatcher
        flow.runFold("")(_ + _) pipeTo sender()
    }
  }
}

class ImplicitMaterializerSpec extends AkkaSpec with ImplicitSender {
  import ImplicitMaterializerSpec._

  "An ImplicitMaterializer" must {

    "provide implicit ActorMaterializer" in {
      val actor = system.actorOf(Props(classOf[SomeActor], List("a", "b", "c")).withDispatcher("akka.test.stream-dispatcher"))
      actor ! "run"
      expectMsg("ABC")
    }
  }
}
