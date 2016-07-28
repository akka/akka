/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream._
import akka.stream.impl.StreamLayout.{ Module, AtomicModule }
import akka.testkit.AkkaSpec

import NewLayout._

class TraversalBuilderSpec extends AkkaSpec {

  class MaterializationResult(
    val connections: Int,
    val inlets:      Array[InPort],
    val outlets:     Array[OutPort]
  ) {

    override def toString = {
      outlets.iterator.zip(inlets.iterator).mkString("connections: ", ", ", "")
    }
  }

  def testMaterialize(b: TraversalBuilder): MaterializationResult = {
    require(b.isComplete, "Traversal builder must be complete")

    val connections = b.inSlots
    val inlets = Array.ofDim[InPort](connections)
    val outlets = Array.ofDim[OutPort](connections)

    var inOffs = 0

    var current: Traversal = b.traversal.get
    var traversalStack: List[Traversal] = current :: Nil

    while (traversalStack.nonEmpty) {
      current = traversalStack.head
      traversalStack = traversalStack.tail

      while (current != EmptyTraversal) {
        current match {
          case MaterializeAtomic(mod, outToSlot) ⇒
            println(s"materialize: $mod inOffs = $inOffs")
            mod.shape.inlets.zipWithIndex.foreach {
              case (in, i) ⇒
                println(s"in $in (id = ${in.id}) assigned to ${inOffs + i}")
                inlets(inOffs + i) = in
            }
            mod.shape.outlets.zipWithIndex.foreach {
              case (out, i) ⇒
                println(s"out $out (id = ${out.id}) assigned to ${inOffs} + ${outToSlot(out.id)} =" +
                  s" ${inOffs + outToSlot(out.id)}")
                outlets(inOffs + outToSlot(out.id)) = out
            }
            inOffs += mod.shape.inlets.size
            current = current.next
          case Concat(first, next) ⇒
            traversalStack = next :: traversalStack
            current = first
          case _ ⇒
            current = current.next
        }
      }
    }

    new MaterializationResult(connections, inlets, outlets)
  }

  def printTraversal(t: Traversal, indent: Int = 0): Unit = {
    var current: Traversal = t
    var slot = 0

    def prindent(s: String): Unit = println(" | " * indent + s)

    while (current != EmptyTraversal) {
      current match {
        case _: PushMaterializedValue           ⇒ prindent("push mat")
        case _: PopMaterializedValue            ⇒ prindent("pop mat")
        case TapMaterializedValue(src, _)       ⇒ prindent("tap mat " + src)
        case AddAttributes(attr, _)             ⇒ prindent("add attr " + attr)
        case MaterializeAtomic(mod, outToSlots) ⇒ prindent("materialize " + mod + " " + outToSlots.mkString("[", ", ", "]"))
        case Concat(first, _) ⇒
          prindent(s"concat")
          printTraversal(first, indent + 1)
        case _ ⇒
      }

      current = current.next
    }
  }

  "TraversalBuilder" must {

    class TestSource extends AtomicModule {
      val out = Outlet[Any]("testSource.out")
      override val shape: Shape = SourceShape(out)
      override val attributes: Attributes = Attributes.name("testSource")
      val traversal = TraversalBuilder.atomic(this)

      override def withAttributes(attributes: Attributes): Module = ???
      override def carbonCopy: Module = ???
      override def replaceShape(s: Shape): Module = ???
      override def toString = "TestSource"
    }

    class TestSink extends AtomicModule {
      val in = Inlet[Any]("testSink.in")
      override val shape: Shape = SinkShape(in)
      override val attributes: Attributes = Attributes.name("testSink")
      val traversal = TraversalBuilder.atomic(this)

      override def withAttributes(attributes: Attributes): Module = ???
      override def carbonCopy: Module = ???
      override def replaceShape(s: Shape): Module = ???
      override def toString = "TestSink"
    }

    class TestFlow(tag: String) extends AtomicModule {
      val in = Inlet[Any](s"testFlow$tag.in")
      val out = Outlet[Any](s"testFlow$tag.out")
      override val shape: Shape = FlowShape(in, out)
      override val attributes: Attributes = Attributes.name(s"testFlow$tag")
      val traversal = TraversalBuilder.atomic(this)

      override def withAttributes(attributes: Attributes): Module = ???
      override def carbonCopy: Module = ???
      override def replaceShape(s: Shape): Module = ???
      override def toString = s"TestFlow$tag"
    }

    val source = new TestSource
    val sink = new TestSink
    val flow1 = new TestFlow("1")
    val flow2 = new TestFlow("2")

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
