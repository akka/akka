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
    val out = Outlet[Any]("testSourceC.out")
    override val shape: Shape = SourceShape(out)
    override val attributes: Attributes = Attributes.name("testSource")
    val traversal = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSource"
  }

  class CompositeTestSink extends AtomicModule {
    val in = Inlet[Any]("testSinkC.in")
    override val shape: Shape = SinkShape(in)
    override val attributes: Attributes = Attributes.name("testSink")
    val traversal = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSink"
  }

  class CompositeTestFlow(tag: String) extends AtomicModule {
    val in = Inlet[Any](s"testFlowC$tag.in")
    val out = Outlet[Any](s"testFlowC$tag.out")
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

  /**
   * This test method emulates a materialization run. It simply puts input and output ports into slots of an Array.
   * After running this method, it can be tested that ports that are meant to be wired together have been put into
   * corresponding slots of the [[MaterializationResult]].
   */
  def testMaterialize(b: TraversalBuilder): MaterializationResult = {
    require(b.isTraversalComplete, "Traversal builder must be complete")

    val connections = b.inSlots
    val inlets = Array.ofDim[InPort](connections)
    val outlets = Array.ofDim[OutPort](connections)

    // Track next assignable number for input ports
    var inOffs = 0

    var current: Traversal = b.traversal.get
    val traversalStack = new java.util.ArrayList[Traversal](16)
    traversalStack.add(current)

    // Due to how Concat works, we need a stack. This probably can be optimized for the most common cases.
    while (!traversalStack.isEmpty) {
      current = traversalStack.remove(traversalStack.size() - 1)

      while (current ne EmptyTraversal) {
        current match {
          case MaterializeAtomic(mod, outToSlot) ⇒
            println(s"materialize: $mod inOffs = $inOffs")

            var i = 0
            val inletsIter = mod.shape.inlets.iterator
            while (inletsIter.hasNext) {
              val in = inletsIter.next()
              println(s"in $in (id = ${in.id}) assigned to ${inOffs + i}")
              inlets(inOffs + i) = in
              i += 1
            }

            val outletsIter = mod.shape.outlets.iterator
            while (outletsIter.hasNext) {
              val out = outletsIter.next()
              println(s"out $out (id = ${out.id}) assigned to ${inOffs} + ${outToSlot(out.id)} =" +
                s" ${inOffs + outToSlot(out.id)}")
              outlets(inOffs + outToSlot(out.id)) = out
            }
            inOffs += mod.shape.inlets.size
            current = current.next
          // And that's it ;)
          case Concat(first, next) ⇒
            traversalStack.add(next)
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
