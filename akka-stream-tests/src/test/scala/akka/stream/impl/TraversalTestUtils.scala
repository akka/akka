/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.impl.NewLayout._
import akka.stream.impl.StreamLayout.{ AtomicModule, Module }
import akka.stream._

object TraversalTestUtils {

  // --- These test classes do not use the optimized linear builder, for testing the composite builder instead
  class CompositeTestSource extends AtomicModule {
    val out = Outlet[Any]("testSource.out")
    override val shape: Shape = SourceShape(out)
    override val attributes: Attributes = Attributes.name("testSource")
    val traversal = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSource"
  }

  class CompositeTestSink extends AtomicModule {
    val in = Inlet[Any]("testSink.in")
    override val shape: Shape = SinkShape(in)
    override val attributes: Attributes = Attributes.name("testSink")
    val traversal = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSink"
  }

  class CompositeTestFlow(tag: String) extends AtomicModule {
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

  // --- These test classes DO use the optimized linear builder, for testing the composite builder instead
  class LinearTestSource extends AtomicModule {
    val out = Outlet[Any]("testSource.out")
    override val shape: Shape = SourceShape(out)
    override val attributes: Attributes = Attributes.name("testSource")
    val traversal = TraversalBuilder.linear(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSource"
  }

  class LinearTestSink extends AtomicModule {
    val in = Inlet[Any]("testSink.in")
    override val shape: Shape = SinkShape(in)
    override val attributes: Attributes = Attributes.name("testSink")
    val traversal = TraversalBuilder.linear(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSink"
  }

  class LinearTestFlow(tag: String) extends AtomicModule {
    val in = Inlet[Any](s"testFlow$tag.in")
    val out = Outlet[Any](s"testFlow$tag.out")
    override val shape: Shape = FlowShape(in, out)
    override val attributes: Attributes = Attributes.name(s"testFlow$tag")
    val traversal = TraversalBuilder.linear(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = s"TestFlow$tag"
  }

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

}
