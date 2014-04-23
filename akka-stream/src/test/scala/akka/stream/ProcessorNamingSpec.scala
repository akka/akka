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
import akka.stream.impl.FlowNameCounter

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ProcessorNamingSpec extends AkkaSpec("akka.loglevel=INFO") {

  def self = ActorBasedFlowMaterializer.currentActorContext().self
  def flowCount = FlowNameCounter(system).counter.get

  "Processors of a flow" must {

    "have sensible default names for flow with one step" in {
      val materializer = FlowMaterializer(MaterializerSettings())
      Flow(List(1)).map(in ⇒ { testActor ! self; in }).consume(materializer)
      expectMsgType[ActorRef].path.name should be(s"flow-$flowCount-1-map")
    }

    "have sensible default names for flow with several steps" in {
      val materializer = FlowMaterializer(MaterializerSettings())
      Flow(List(1)).
        map(in ⇒ { testActor ! self; in }).
        transform(new Transformer[Int, Int] {
          override def onNext(in: Int) = { testActor ! self; List(in) }
        }).
        filter(_ ⇒ { testActor ! self; true }).
        consume(materializer)

      expectMsgType[ActorRef].path.name should be(s"flow-$flowCount-1-map")
      expectMsgType[ActorRef].path.name should be(s"flow-$flowCount-2-transform")
      expectMsgType[ActorRef].path.name should be(s"flow-$flowCount-3-filter")
    }

    "use specified flow namePrefix in materializer" in {
      val materializer = FlowMaterializer(MaterializerSettings(), namePrefix = Some("myflow"))
      Flow(List(1)).map(in ⇒ { testActor ! self; in }).consume(materializer)
      expectMsgType[ActorRef].path.name should be(s"myflow-$flowCount-1-map")
    }

    "use specified withNamePrefix in materializer" in {
      val materializer = FlowMaterializer(MaterializerSettings())
      Flow(List(2)).map(in ⇒ { testActor ! self; in }).consume(materializer.withNamePrefix("myotherflow"))
      expectMsgType[ActorRef].path.name should be(s"myotherflow-$flowCount-1-map")
    }

    "create unique name for each materialization" in {
      val materializer = FlowMaterializer(MaterializerSettings(), namePrefix = Some("myflow"))
      val flow = Flow(List(1)).map(in ⇒ { testActor ! self; in })
      flow.consume(materializer)
      val name1 = expectMsgType[ActorRef].path.name
      flow.consume(materializer)
      val name2 = expectMsgType[ActorRef].path.name
      name1 should not be (name2)
    }

  }

}