/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.actor.ActorSystem
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MaterializerWithAttributesSpec
    extends TestKit(ActorSystem("MatWithAttributesSpec"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {
  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val materializer: Materializer = Materializer(system, Attributes.name("foo"))

  val attributesSource = new GraphStageWithMaterializedValue[SourceShape[Nothing], Attributes] {
    val out = Outlet[Nothing]("AttributesSource.out")
    override val shape = SourceShape(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Attributes) =
      new GraphStageLogic(shape) with OutHandler {
        override def preStart(): Unit = completeStage()
        override def onPull(): Unit = completeStage()

        setHandler(out, this)
      } -> inheritedAttributes
  }

  "MaterializerWithAttributes" should {
    "apply its attributes to streams it materializes" in {
      val attributes = Source.fromGraph(attributesSource).to(Sink.ignore).run()
      attributes.get[Attributes.Name].map(_.n) should contain("foo")

      Source
        .fromGraph(attributesSource)
        .to(Sink.ignore)
        .run()(Materializer(system))
        .get[Attributes.Name]
        .map(_.n) shouldNot contain("foo")
    }
  }
}
