/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

//#imports

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.PartialFlowGraph

//#imports

import akka.stream.testkit.AkkaSpec

// TODO replace ⇒ with => and disable this intellij setting
class StreamPartialFlowGraphDocSpec extends AkkaSpec {

  implicit val mat = FlowMaterializer()

  "build with open ports" in {
    //#simple-partial-flow-graph
    PartialFlowGraph { implicit b ⇒

    }
    //#simple-partial-flow-graph
  }

}
