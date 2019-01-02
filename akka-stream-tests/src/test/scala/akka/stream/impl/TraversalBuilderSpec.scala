/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.NotUsed
import akka.stream._
import akka.stream.impl.TraversalTestUtils._
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.AkkaSpec

import scala.concurrent.Await

class TraversalBuilderSpec extends AkkaSpec {

  "CompositeTraversalBuilder" must {
    val source = new CompositeTestSource
    val sink = new CompositeTestSink
    val flow1 = new CompositeTestFlow("1")
    val flow2 = new CompositeTestFlow("2")

    // ADD closed shape, (and composite closed shape)

    "work with a single Source and Sink" in {
      val builder =
        source.traversalBuilder
          .add(sink.traversalBuilder, sink.shape, Keep.left)
          .wire(source.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(1)

      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a nested Source and Sink" in {
      val nestedBuilder =
        TraversalBuilder.empty()
          .add(source.traversalBuilder, source.shape, Keep.left)

      val builder =
        sink.traversalBuilder
          .add(nestedBuilder, source.shape, Keep.left)
          .wire(source.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(1)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a remapped Source and Sink" in {
      val remappedShape = SourceShape(Outlet[Any]("remapped.out"))
      remappedShape.out.mappedTo = source.out

      val builder = sink.traversalBuilder
        .add(source.traversalBuilder, remappedShape, Keep.left)
        .wire(remappedShape.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(1)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with two Flows" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.left)
        .add(flow2.traversalBuilder, flow2.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, flow2.in)
        .wire(flow2.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with two Flows wired in opposite order" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.left)
        .add(flow2.traversalBuilder, flow2.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(flow2.out, sink.in)
        .wire(flow1.out, flow2.in)
        .wire(source.out, flow1.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with two Flows wired in an irregular order" in {
      val builder = source.traversalBuilder
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .add(flow2.traversalBuilder, flow2.shape, Keep.left)
        .wire(flow2.out, sink.in)
        .add(flow1.traversalBuilder, flow1.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, flow2.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
    }

    "work with a Flow wired to its imported self" in {
      val remappedShape = flow1.shape.deepCopy()

      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.left)
        .add(flow1.traversalBuilder, remappedShape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, remappedShape.in)
        .wire(remappedShape.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow1.in)
      mat.outlets(2) should ===(flow1.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with a nested Flow chain" in {
      val nestedFlowShape = FlowShape(flow1.in, flow2.out)

      val nestedFlows =
        flow1.traversalBuilder
          .add(flow2.traversalBuilder, flow2.shape, Keep.left)
          .wire(flow1.out, flow2.in)

      val builder = source.traversalBuilder
        .add(nestedFlows, nestedFlowShape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow2.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with a nested Flow chain, imported" in {
      val importedFlowShape = FlowShape(Inlet[Any]("imported.in"), Outlet[Any]("imported.out"))
      importedFlowShape.in.mappedTo = flow1.in
      importedFlowShape.out.mappedTo = flow2.out

      val nestedFlows =
        flow1.traversalBuilder
          .add(flow2.traversalBuilder, flow2.shape, Keep.left)
          .wire(flow1.out, flow2.in)

      val builder = source.traversalBuilder
        .add(nestedFlows, importedFlowShape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, importedFlowShape.in)
        .wire(importedFlowShape.out, sink.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with a Flow wired to self" in {
      val builder = flow1.traversalBuilder.wire(flow1.out, flow1.in)

      val mat = testMaterialize(builder)

      mat.connections should ===(1)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(flow1.in)
    }

    "properly materialize empty builder" in {
      val builder = TraversalBuilder.empty()

      val mat = testMaterialize(builder)
      mat.connections should ===(0)
      mat.outlets.length should ===(0)
      mat.inlets.length should ===(0)
      mat.matValue should ===(NotUsed)
    }

    "properly propagate materialized value with Keep.left" in {
      val builder =
        source.traversalBuilder
          .add(sink.traversalBuilder, sink.shape, Keep.left)
          .wire(source.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===("TestSource")
    }

    "keep mapped materialized value of empty builder" in {
      val builder =
        TraversalBuilder.empty()
          .transformMat((_: Any) ⇒ "NOTUSED")
          .add(source.traversalBuilder, source.shape, Keep.left)
          .add(sink.traversalBuilder, sink.shape, Keep.left)
          .wire(source.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===("NOTUSED")
    }

    "properly propagate materialized value with Keep.right" in {
      val builder =
        source.traversalBuilder
          .add(sink.traversalBuilder, sink.shape, Keep.right)
          .wire(source.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===("TestSink")
    }

    "properly propagate materialized value with Keep.both" in {
      val builder =
        source.traversalBuilder
          .add(sink.traversalBuilder, sink.shape, Keep.both)
          .wire(source.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===(("TestSource", "TestSink"))
    }

    "properly propagate materialized value with Keep.left with Flow in middle" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===("TestSource")
    }

    "properly propagate materialized value with Keep.right with Flow in middle (1)" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.right)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===("TestFlow1")
    }

    "properly propagate materialized value with Keep.right with Flow in middle (2)" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.right)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===("TestSink")
    }

    "properly propagate materialized value with Keep.both with Flow in middle (1)" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.both)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===(("TestSource", "TestFlow1"))
    }

    "properly propagate materialized value with Keep.both with Flow in middle (2)" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.both)
        .add(sink.traversalBuilder, sink.shape, Keep.both)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)

      val mat = testMaterialize(builder)

      mat.matValue should ===((("TestSource", "TestFlow1"), "TestSink"))
    }

    "properly map materialized value" in {
      val builder = source.traversalBuilder
        .add(flow1.traversalBuilder, flow1.shape, Keep.right)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)
        .transformMat("MAPPED: " + (_: String))

      val mat = testMaterialize(builder)

      mat.matValue should ===("MAPPED: TestFlow1")
    }

    "properly map materialized value (nested)" in {
      val flowBuilder =
        flow1.traversalBuilder
          .transformMat("M1: " + (_: String))

      val builder = source.traversalBuilder
        .add(flowBuilder, flow1.shape, Keep.right)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)
        .transformMat("M2: " + (_: String))

      val mat = testMaterialize(builder)

      mat.matValue should ===("M2: M1: TestFlow1")
    }

    "properly set attributes for whole chain" in {
      val builder = source.traversalBuilder
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, sink.in)
        .setAttributes(Attributes.name("test"))

      val mat = testMaterialize(builder)

      mat.attributesAssignments should ===(List(
        source → (Attributes.name("test") and Attributes.name("testSource")),
        sink → (Attributes.name("test") and Attributes.name("testSink"))
      ))
    }

    "overwrite last attributes until embedded in other builder" in {
      val innerBuilder = source.traversalBuilder
        .add(sink.traversalBuilder.setAttributes(Attributes.name("testSinkB")), sink.shape, Keep.left)
        .wire(source.out, sink.in)
        .setAttributes(Attributes.name("test"))
        .setAttributes(Attributes.name("test2"))

      val builder =
        TraversalBuilder.empty()
          .add(innerBuilder, ClosedShape, Keep.left)
          .setAttributes(Attributes.name("outer"))
          .setAttributes(Attributes.name("outer2"))

      val mat = testMaterialize(builder)

      mat.attributesAssignments should ===(List(
        source → (Attributes.name("outer2") and Attributes.name("test2") and Attributes.name("testSource")),
        sink → (Attributes.name("outer2") and Attributes.name("test2") and Attributes.name("testSinkB"))
      ))
    }

    "propagate attributes to embedded flow" in {
      val flowBuilder =
        flow1.traversalBuilder
          .setAttributes(Attributes.name("flow"))

      val builder = source.traversalBuilder
        .add(flowBuilder, flow1.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)
        .setAttributes(Attributes.name("test"))

      val mat = testMaterialize(builder)

      mat.attributesAssignments should ===(List(
        source → (Attributes.name("test") and Attributes.name("testSource")),
        flow1 → (Attributes.name("test") and Attributes.name("flow")),
        sink → (Attributes.name("test") and Attributes.name("testSink"))
      ))
    }

    "properly track embedded island and its attributes" in {
      val flowBuilder =
        flow1.traversalBuilder
          .makeIsland(TestIsland1)
          .setAttributes(Attributes.name("flow"))

      val builder = source.traversalBuilder
        .add(flowBuilder, flow1.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)
        .setAttributes(Attributes.name("test"))

      val mat = testMaterialize(builder)

      mat.islandAssignments should ===(List(
        (source, Attributes.none, TestDefaultIsland),
        (flow1, Attributes.name("test") and Attributes.name("flow"), TestIsland1),
        (sink, Attributes.none, TestDefaultIsland)
      ))
    }

    "properly ignore redundant island assignment" in {
      val flowBuilder =
        flow1.traversalBuilder
          .makeIsland(TestIsland1)
          .makeIsland(TestIsland2)
          .setAttributes(Attributes.name("flow"))

      val builder = source.traversalBuilder
        .add(flowBuilder, flow1.shape, Keep.left)
        .add(sink.traversalBuilder, sink.shape, Keep.left)
        .wire(source.out, flow1.in)
        .wire(flow1.out, sink.in)
        .setAttributes(Attributes.name("test"))

      val mat = testMaterialize(builder)

      mat.islandAssignments should ===(List(
        (source, Attributes.none, TestDefaultIsland),
        (flow1, Attributes.name("test") and Attributes.name("flow"), TestIsland1),
        (sink, Attributes.none, TestDefaultIsland)
      ))
    }

    //TODO: Dummy test cases just for smoke-testing. Should be removed.

    "foo" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      import scala.concurrent.duration._

      val graph = Source.repeat(1).take(10).toMat(Sink.fold(0)(_ + _))(Keep.right)

      Await.result(graph.run(), 3.seconds) should ===(10)
    }

    "islands 1" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      val sub = TestSubscriber.probe[Int]()
      val graph = Source.repeat(1).take(10).toMat(Sink.asPublisher(false))(Keep.right)

      val pub = graph.run().subscribe(sub)

      sub.request(10)
      sub.expectNextN(List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      sub.expectComplete()
    }

    "islands 2" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      val pub = TestPublisher.probe[Int]()
      import scala.concurrent.duration._

      val graph = Source.asSubscriber[Int].toMat(Sink.fold(0)(_ + _))(Keep.both)

      val (sub, future) = graph.run()
      pub.subscribe(sub)

      pub.sendNext(0)
      pub.sendNext(1)
      pub.sendNext(2)
      pub.sendNext(3)
      pub.sendComplete()

      Await.result(future, 3.seconds) should ===(6)
    }

    "islands 3" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      val sub = TestSubscriber.probe[Int]()
      Source
        .repeat(1)
        .take(10)
        .runWith(Sink.fromSubscriber(sub))

      sub.request(10)
      sub.expectNextN(List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      sub.expectComplete()
    }

    "islands 4" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      val pub = TestPublisher.probe[Int]()
      import scala.concurrent.duration._

      val future = Source.fromPublisher(pub).runWith(Sink.fold(0)(_ + _))
      pub.sendNext(0)
      pub.sendNext(1)
      pub.sendNext(2)
      pub.sendNext(3)
      pub.sendComplete()

      Await.result(future, 3.seconds) should ===(6)
    }

    "bidiflow1" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      val flow1 = Flow.fromGraph(new fusing.Map((x: Int) ⇒ x + 1))
      val flow2 = Flow.fromGraph(new fusing.Map((x: Int) ⇒ x + 1))

      val bidi = BidiFlow.fromFlowsMat(flow1, flow2)(Keep.none)

      val flow = bidi.join(Flow[Int])

      Source.single(1).via(flow).runWith(Sink.ignore)
    }

    "bidiflow reverse" in {
      implicit val mat = PhasedFusingActorMaterializer(ActorMaterializerSettings(system).withSyncProcessingLimit(5000))
      val flow1 = Flow.fromGraph(new fusing.Map((x: Int) ⇒ x + 1))
      val flow2 = Flow.fromGraph(new fusing.Map((x: Int) ⇒ x + 1))

      val bidi = BidiFlow.fromFlowsMat(flow1, flow2)(Keep.none)

      val flow = Flow[Int].join(bidi.reversed)

      Source.single(1).via(flow).runWith(Sink.ignore)
    }

  }

}
