/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream._
import akka.stream.impl.NewLayout._
import akka.stream.impl.StreamLayout.{ AtomicModule, Module }
import akka.stream.impl.TraversalTestUtils._
import akka.testkit.AkkaSpec

class LinearTraversalBuilderSpec extends AkkaSpec {

  "LinearTraversalBuilder" must {
    val source = new LinearTestSource
    val sink = new LinearTestSink
    val flow1 = new LinearTestFlow("1")
    val flow2 = new LinearTestFlow("2")

    val compositeSource = new CompositeTestSource
    val compositeSink = new CompositeTestSink
    val compositeFlow1 = new CompositeTestFlow("C1")
    val compositeFlow2 = new CompositeTestFlow("C2")

    // ADD closed shape, (and composite closed shape)

    "work with a single Source and Sink" in {
      println(source.traversal
        .append(sink.traversal, sink.shape))

      val builder =
        source.traversal
          .append(sink.traversal, sink.shape)

      println(builder)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(1)

      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with two Flows" in {
      val builder = source.traversal
        .append(flow1.traversal, flow1.shape)
        .append(flow2.traversal, flow2.shape)
        .append(sink.traversal, sink.shape)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with two Flows wired in opposite order" in {
      val s1 = flow2.traversal.append(sink.traversal, sink.shape)
      val s2 = flow1.traversal.append(s1, SinkShape(flow2.in))
      val builder = source.traversal.append(s2, SinkShape(flow1.in))

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with two Flows wired in an irregular order" in {
      val source2 = source.traversal.append(flow1.traversal, flow1.shape)
      val sink2 = flow2.traversal.append(sink.traversal, sink.shape)

      val builder = source2.append(sink2, SinkShape(flow2.in))

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a Flow appended to its imported self" in {
      val remappedShape = FlowShape(Inlet[Any]("Remapped.in"), Outlet[Any]("Remapped.out"))
      remappedShape.in.mappedTo = flow1.in
      remappedShape.out.mappedTo = flow1.out

      val builder = source.traversal
        .append(flow1.traversal, flow1.shape)
        .append(flow1.traversal, flow1.shape)
        .append(sink.traversal, sink.shape)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow1.in)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a nested Flow chain" in {
      val nestedFlows =
        flow1.traversal
          .append(flow2.traversal, flow2.shape)

      println(nestedFlows)

      val builder = source.traversal
        .append(nestedFlows, FlowShape(flow1.in, flow2.out))
        .append(sink.traversal, sink.shape)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
    }

    "work with a nested Flow chain used twice (appended to self)" in {
      val nestedFlows =
        flow1.traversal
          .append(flow2.traversal, flow2.shape)

      println(nestedFlows)

      val builder = source.traversal
        .append(nestedFlows, FlowShape(flow1.in, flow2.out))
        .append(nestedFlows, FlowShape(flow1.in, flow2.out))
        .append(sink.traversal, sink.shape)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(5)
      mat.outlets(4) should ===(source.out)
      mat.inlets(4) should ===(flow1.in)
      mat.outlets(3) should ===(flow1.out)
      mat.inlets(3) should ===(flow2.in)
      mat.outlets(2) should ===(flow2.out)
      mat.inlets(2) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(sink.in)
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

    "work with a two Flows wired back to self" in {
      val builder =
        flow1.traversal
          .append(flow2.traversal, flow2.shape)
          .wire(flow2.out, flow1.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(2)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(flow2.in)
      mat.outlets(1) should ===(flow2.out)
      mat.inlets(1) should ===(flow1.in)
    }

    "work with Flow appended to self then wired back to self" in {
      val builder =
        flow1.traversal
          .append(flow1.traversal, flow1.shape)
          .wire(flow1.out, flow1.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(2)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(flow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow1.in)
    }

    "be able to be used with a composite source" in {
      val builder =
        compositeSource.traversal
          .add(sink.traversal, sink.shape)
          .wire(compositeSource.out, sink.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(1)
      mat.outlets(0) should ===(compositeSource.out)
      mat.inlets(0) should ===(sink.in)
    }

    "be able to be used with a composite sink" in {
      val builder =
        compositeSink.traversal
          .add(source.traversal, source.shape)
          .wire(source.out, compositeSink.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(1)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(compositeSink.in)
    }

    "be able to be joined with a composite flow" in {
      val embeddedFlow =
        flow1.traversal
          .append(flow2.traversal, flow2.shape)

      val builder =
        compositeFlow1.traversal
          .add(embeddedFlow, FlowShape(flow1.in, flow2.out))
          .wire(compositeFlow1.out, flow1.in)
          .wire(flow2.out, compositeFlow1.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(0) should ===(flow2.out)
      mat.inlets(0) should ===(compositeFlow1.in)
      mat.outlets(1) should ===(flow1.out)
      mat.inlets(1) should ===(flow2.in)
      mat.outlets(2) should ===(compositeFlow1.out)
      mat.inlets(2) should ===(flow1.in)
    }

    "be able to use a linear flow with composite source and sink" in {
      val builder =
        compositeSource.traversal
          .add(compositeSink.traversal, compositeSink.shape)
          .add(flow1.traversal, flow1.shape)
          .wire(compositeSource.out, flow1.in)
          .wire(flow1.out, compositeSink.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(2)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(compositeSink.in)
      mat.outlets(1) should ===(compositeSource.out)
      mat.inlets(1) should ===(flow1.in)
    }

    "be able to add a flow to an empty composite and join to itself" in {
      val builder =
        CompositeTraversalBuilder()
          .add(flow1.traversal, flow1.shape)
          .wire(flow1.out, flow1.in)

      printTraversal(builder.traversal.get)

      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(1)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(flow1.in)
    }

    "be able to embed a composite sink in a linear traversal" in {
      val builder =
        source.traversal
          .append(compositeSink.traversal, compositeSink.shape)

      println(builder)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(1)
      mat.outlets(0) should ===(source.out)
      mat.inlets(0) should ===(compositeSink.in)
    }

    "be able to start from a composite source" in pending

    "be able to start from a composite flow" in pending

    "be able to embed a composite flow in a linear traversal" in {
      val builder =
        source.traversal
          .append(compositeFlow1.traversal, compositeFlow1.shape)
          .append(sink.traversal, sink.shape)

      println(builder)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(2)
      mat.outlets(1) should ===(source.out)
      mat.inlets(1) should ===(compositeFlow1.in)
      mat.outlets(0) should ===(compositeFlow1.out)
      mat.inlets(0) should ===(sink.in)
    }

    "be able to embed a composite sink with an irregular wiring" in {
      val sinkBuilder =
        CompositeTraversalBuilder()
          .add(compositeFlow2.traversal, compositeFlow2.shape)
          .add(compositeSink.traversal, compositeSink.shape)
          .add(compositeFlow1.traversal, compositeFlow1.shape)
          .wire(compositeFlow2.out, compositeSink.in)
          .wire(compositeFlow1.out, compositeFlow2.in)

      val builder =
        source.traversal
          .append(sinkBuilder, SinkShape(compositeFlow1.in))

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      println(mat)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(compositeFlow1.in)
      mat.outlets(1) should ===(compositeFlow2.out)
      mat.inlets(1) should ===(compositeSink.in)
      mat.outlets(0) should ===(compositeFlow1.out)
      mat.inlets(0) should ===(compositeFlow2.in)
    }

    "be able to embed a composite flow multiple times appended to self" in {
      val builder =
        source.traversal
          .append(compositeFlow1.traversal, compositeFlow1.shape)
          .append(compositeFlow1.traversal, compositeFlow1.shape)
          .append(sink.traversal, sink.shape)

      println(builder)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)

      mat.connections should ===(3)
      mat.outlets(2) should ===(source.out)
      mat.inlets(2) should ===(compositeFlow1.in)
      mat.outlets(1) should ===(compositeFlow1.out)
      mat.inlets(1) should ===(compositeFlow1.in)
      mat.outlets(0) should ===(compositeFlow1.out)
      mat.inlets(0) should ===(sink.in)
    }

    "be able to embed a composite flow multiple times appended to self alternating with linear flow" in {

      val builder =
        source.traversal
          .append(compositeFlow1.traversal, compositeFlow1.shape)
          .append(flow1.traversal, flow1.shape)
          .append(compositeFlow1.traversal, compositeFlow1.shape)
          .append(flow1.traversal, flow1.shape)
          .append(sink.traversal, sink.shape)

      println(builder)

      printTraversal(builder.traversal.get)
      val mat = testMaterialize(builder)
      println(mat)

      mat.connections should ===(5)

      mat.outlets(4) should ===(source.out)
      mat.inlets(4) should ===(compositeFlow1.in)
      mat.outlets(3) should ===(compositeFlow1.out)
      mat.inlets(3) should ===(flow1.in)
      mat.outlets(2) should ===(flow1.out)
      mat.inlets(2) should ===(compositeFlow1.in)
      mat.outlets(1) should ===(compositeFlow1.out)
      mat.inlets(1) should ===(flow1.in)
      mat.outlets(0) should ===(flow1.out)
      mat.inlets(0) should ===(sink.in)
    }

  }

}
