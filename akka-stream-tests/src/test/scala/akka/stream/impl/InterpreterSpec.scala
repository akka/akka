/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.testkit.AkkaSpec
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.StreamTestKit._
import akka.stream.impl.fusing.ActorInterpreter

class InterpreterSpec extends AkkaSpec {
  import FlowGraph.Implicits._

  implicit val mat = FlowMaterializer()

  class Setup {
    val up = PublisherProbe[Int]
    val down = SubscriberProbe[Int]
    private val props = ActorInterpreter.props(mat.settings, List(fusing.Map { x: Any ⇒ x })).withDispatcher("akka.test.stream-dispatcher")
    val processor = ActorProcessorFactory[Int, Int](system.actorOf(props))
  }

  "An ActorInterpreter" must {

    "pass along early cancellation" in new Setup {
      processor.subscribe(down)
      val sub = down.expectSubscription()
      sub.cancel()
      up.subscribe(processor)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

  }

}