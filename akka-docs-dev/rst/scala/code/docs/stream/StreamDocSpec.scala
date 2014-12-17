/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Zip
import akka.stream.testkit.AkkaSpec

// TODO replace ⇒ with => and disable this intellij setting
class StreamDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  //#imports
  import akka.stream.FlowMaterializer
  import akka.stream.scaladsl.Broadcast
  //#imports

  implicit val mat = FlowMaterializer()

}
