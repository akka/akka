/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.scaladsl.Flow
import akka.actor.ActorContext
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorRef
import scala.collection.immutable.TreeSet
import scala.util.control.NonFatal
import akka.stream.impl.ActorBasedFlowMaterializer

class ProcessorHierarchySpec extends AkkaSpec("akka.actor.debug.lifecycle=off\nakka.loglevel=INFO") {

  val materializer = FlowMaterializer(MaterializerSettings())

  def self = ActorBasedFlowMaterializer.currentActorContext().self

  "An ActorBasedFlowMaterializer" must {

    "generate the right level of descendants" in {
      val f = Flow(() ⇒ {
        testActor ! self
        Flow(List(1)).map(x ⇒ { testActor ! self; x }).toProducer(materializer)
      }).take(3).foreach(x ⇒ {
        testActor ! self
        Flow(x).foreach(_ ⇒ testActor ! self).consume(materializer)
      }).toFuture(materializer)
      Await.result(f, 3.seconds)
      val refs = receiveWhile(idle = 250.millis) {
        case r: ActorRef ⇒ r
      }
      try {
        refs.toSet.size should be(8)
        refs.distinct.map(_.path.elements.size).groupBy(x ⇒ x).mapValues(x ⇒ x.size) should be(Map(2 -> 2, 3 -> 6))
      } catch {
        case NonFatal(e) ⇒
          println(refs.map(_.toString).to[TreeSet].mkString("\n"))
          throw e
      }
    }

  }

}