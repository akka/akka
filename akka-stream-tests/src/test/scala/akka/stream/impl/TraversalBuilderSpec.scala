/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream._
import akka.stream.impl.StreamLayout.{ Module, AtomicModule }
import TraversalTestUtils._
import akka.testkit.AkkaSpec

import NewLayout._

class TraversalBuilderSpec extends AkkaSpec {

  "CompositeTraversalBuilder" must {
    val source = new CompositeTestSource
    val sink = new CompositeTestSink
    val flow1 = new CompositeTestFlow("1")
    val flow2 = new CompositeTestFlow("2")

    // ADD closed shape, (and composite closed shape)

    "work with a single Source and Sink" in {
      println(source.traversal
        .add(sink.traversal, sink.shape))

      val builder =
        source.traversal
          .add(sink.traversal, sink.shape)
          .wire(source.out, sink.in)

      println(builder)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(1)

      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a nested Source and Sink" in {
      val nestedBuilder =
        CompositeTraversalBuilder().add(source.traversal, source.shape)

      val builder =
        sink.traversal
          .add(nestedBuilder, source.shape)
          .wire(source.out, sink.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(1)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a remapped Source and Sink" in {
      val remappedShape = SourceShape(Outlet[Any]("remapped.out"))
      remappedShape.out.mappedTo = source.out

      val builder = sink.traversal
        .add(source.traversal, remappedShape)
        .wire(remappedShape.out, sink.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(1)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with two Flows" in {
      val builder = source.traversal
        .add(flow1.traversal, flow1.shape)
        .add(flow2.traversal, flow2.shape)
        .add(sink.traversal, sink.shape)
        .wire(source.out, flow1.in)
        .wire(flow1.out, flow2.in)
        .wire(flow2.out, sink.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with two Flows wired in opposite order" in {
      val builder = source.traversal
        .add(flow1.traversal, flow1.shape)
        .add(flow2.traversal, flow2.shape)
        .add(sink.traversal, sink.shape)
        .wire(flow2.out, sink.in)
        .wire(flow1.out, flow2.in)
        .wire(source.out, flow1.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with two Flows wired in an irregular order" in {
      println(source.traversal
        .add(sink.traversal, sink.shape)
        .add(flow2.traversal, flow2.shape)
        .wire(flow2.out, sink.in))

      val builder = source.traversal
        .add(sink.traversal, sink.shape)
        .add(flow2.traversal, flow2.shape)
        .wire(flow2.out, sink.in)
        .add(flow1.traversal, flow1.shape)
        .wire(source.out, flow1.in)
        .wire(flow1.out, flow2.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
    }

    "work with a Flow wired to its imported self" in {
      val remappedShape = FlowShape(Inlet[Any]("Remapped.in"), Outlet[Any]("Remapped.out"))
      remappedShape.in.mappedTo = flow1.in
      remappedShape.out.mappedTo = flow1.out

      val builder = source.traversal
        .add(flow1.traversal, flow1.shape)
        .add(flow1.traversal, remappedShape)
        .add(sink.traversal, sink.shape)
        .wire(source.out, flow1.in)
        .wire(flow1.out, remappedShape.in)
        .wire(remappedShape.out, sink.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

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
        flow1.traversal
          .add(flow2.traversal, flow2.shape)
          .wire(flow1.out, flow2.in)

      println(nestedFlows)

      println(source.traversal
        .add(nestedFlows, nestedFlowShape)
        .add(sink.traversal, sink.shape)
        .wire(source.out, flow1.in))

      val builder = source.traversal
        .add(nestedFlows, nestedFlowShape)
        .add(sink.traversal, sink.shape)
        .wire(source.out, flow1.in)
        .wire(flow2.out, sink.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

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
        flow1.traversal
          .add(flow2.traversal, flow2.shape)
          .wire(flow1.out, flow2.in)

      val builder = source.traversal
        .add(nestedFlows, importedFlowShape)
        .add(sink.traversal, sink.shape)
        .wire(source.out, importedFlowShape.in)
        .wire(importedFlowShape.out, sink.in)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(sink.in)
    }

    "work with a Flow wired to self" in {
      val builder = flow1.traversal.wire(flow1.out, flow1.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(1)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(flow1.in)
    }

    "work with a Flow wired to self embedded in a larger graph" in pending

  }

}
