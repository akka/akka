/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.impl.TraversalTestUtils._
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, Source }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.{ AkkaSpec, ImplicitSender }

import scala.concurrent.Await

class SpecialEmptyMatMaterializerSpec extends AkkaSpec with ImplicitSender {

  val materializer = ActorMaterializer()
  val fastSpecialMat = SpecialEmptyMaterializer(system, materializer.settings, system.dispatchers, system.deadLetters, new AtomicBoolean(false), SeqActorName("FAST"))

  val playEmptyGraph =
    Source.empty[Unit]
      .fold("") { case _ ⇒ "" + "" }
      .viaMat(new GraphStageWithMaterializedValue[FlowShape[String, String], String] {
        val in = Inlet[String]("in")
        val out = Outlet[String]("out")
        override val shape: FlowShape[String, String] = FlowShape(in, out)

        override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) =
          new GraphStageLogic(shape) with InHandler with OutHandler {
            override def onPush(): Unit = ???

            override def onPull(): Unit = pull(in)

            override def preStart(): Unit =
              testActor ! "preStart"

            override def postStop(): Unit =
              testActor ! "postStop"

          } → "MAT"
      })(Keep.right)
      .to(Sink.head)

  val play2EmptyGraph =
    Source.empty[Unit]
      .fold("") { case _ ⇒ "" + "" }
      .viaMat(new GraphStageWithMaterializedValue[FlowShape[String, String], String] {
        val in = Inlet[String]("in")
        val out = Outlet[String]("out")
        override val shape: FlowShape[String, String] = FlowShape(in, out)

        override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) =
          new GraphStageLogic(shape) with InHandler with OutHandler {
            override def onPush(): Unit = ???

            override def onPull(): Unit = pull(in)

            override def preStart(): Unit =
              testActor ! "preStart"

            override def postStop(): Unit =
              testActor ! "postStop"

          } → "MAT"
      })(Keep.right)
      .to(Sink.fold("")(_ + _.toString))

  "SecialOnlyMatMaterializerBuilder" must {

    "work with Play's empty stream case" in {
      val mat = playEmptyGraph.run()(fastSpecialMat)
      mat should ===("MAT")
      expectMsg("preStart")
      expectMsg("postStop")
    }

    "work with more realistic Play's empty stream case" in {
      val mat = play2EmptyGraph.run()(fastSpecialMat)
      mat should ===("MAT")
      expectMsg("preStart")
      expectMsg("postStop")
    }
  }
}
