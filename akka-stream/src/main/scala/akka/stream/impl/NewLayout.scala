/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.impl.StreamLayout.{ AtomicModule, Module }
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream._

import scala.collection.immutable

object NewLayout {

  // Materialized values
  // Attributes
  // MatValueSource
  // Fusing
  sealed trait Traversal {
    def next: Traversal

    def concat(that: Traversal): Traversal = {
      if (that eq EmptyTraversal) this
      else Concat(this, that)
    }
  }

  final case class PushMaterializedValue(next: Traversal) extends Traversal
  final case class PopMaterializedValue(next: Traversal) extends Traversal
  final case class TapMaterializedValue(src: MaterializedValueSource[Any], next: Traversal) extends Traversal

  final case class Concat(first: Traversal, next: Traversal) extends Traversal

  final case class AddAttributes(attributes: Attributes, next: Traversal) extends Traversal

  final case class MaterializeAtomic(module: Module, outToSlots: Array[Int]) extends Traversal {
    override def next: Traversal = EmptyTraversal

    override def toString: String = s"MaterializeAtomic($module, ${outToSlots.mkString("[", ", ", "]")})"
  }

  object EmptyTraversal extends Traversal {
    def next: Traversal =
      throw new IllegalStateException("EmptyTraversal has no next Traversal but next was called.")

    override def concat(that: Traversal): Traversal = that
  }

  private def initShape(shape: Shape): Unit = {
    // Initialize port IDs
    val inIter = shape.inlets.iterator
    var i = 0
    while (inIter.hasNext) {
      inIter.next.id = i
      i += 1
    }

    val outIter = shape.outlets.iterator
    i = 0
    while (outIter.hasNext) {
      outIter.next.id = i
      i += 1
    }
  }

  object TraversalBuilder {
    def atomic(module: AtomicModule): TraversalBuilder = {
      initShape(module.shape)

      if (module.outPorts.isEmpty) {
        CompletedTraversalBuilder(
          traversal = Some(MaterializeAtomic(module, Array.ofDim[Int](module.shape.outlets.size))),
          inSlots = module.shape.inlets.size,
          inToOffset = module.shape.inlets.map(in ⇒ in → in.id).toMap
        )
      } else {
        AtomicTraversalBuilder(
          module,
          Array.ofDim[Int](module.shape.outlets.size),
          module.shape.outlets.size
        )
      }
    }

    val wireBackward: Array[Int] = Array(-1)
    val noWire: Array[Int] = Array()

    def linear(module: AtomicModule): LinearTraversalBuilder = {
      require(module.inPorts.size <= 1, "Modules with more than one input port cannot be linear.")
      require(module.outPorts.size <= 1, "Modules with more than one input port cannot be linear.")
      initShape(module.shape)

      val inPortOpt = module.inPorts.headOption
      val outPortOpt = module.outPorts.headOption

      val wiring = if (outPortOpt.isDefined) wireBackward else noWire

      LinearTraversalBuilder(
        inPortOpt,
        outPortOpt,
        if (inPortOpt.isDefined) 1 else 0,
        traversalSoFar = MaterializeAtomic(module, wiring)
      )
    }
  }

  trait TraversalBuilder {

    def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder

    def wire(out: OutPort, in: InPort): TraversalBuilder

    def offsetOfModule(out: OutPort): Int

    def offsetOf(in: InPort): Int

    def assign(out: OutPort, relativeSlot: Int): TraversalBuilder

    def isComplete: Boolean

    def inSlots: Int

    def traversal: Option[Traversal] = None

    def unwiredOuts: Int
  }

  final case class CompletedTraversalBuilder(override val traversal: Some[Traversal], inSlots: Int, inToOffset: Map[InPort, Int]) extends TraversalBuilder {

    override def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      val key = new BuilderKey
      CompositeTraversalBuilder(
        reverseTraversal = key :: Nil,
        inSlots = inSlots,
        inOffsets = inToOffset,
        pendingBuilders = Map(key → this)
      ).add(submodule, shape)
    }

    override def offsetOf(in: InPort): Int = inToOffset(in)

    override def isComplete: Boolean = true

    override def wire(out: OutPort, in: InPort): TraversalBuilder =
      throw new UnsupportedOperationException("Cannot wire ports in a completed builder.")

    override def unwiredOuts: Int = 0

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder =
      throw new UnsupportedOperationException("Cannot assign ports to slots in a completed builder.")

    override def offsetOfModule(out: OutPort): Int =
      throw new UnsupportedOperationException("Cannot look up offsets in a completed builder.")

  }

  final case class AtomicTraversalBuilder(module: AtomicModule, outToSlot: Array[Int], unwiredOuts: Int) extends TraversalBuilder {

    override def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      CompositeTraversalBuilder().add(this, module.shape).add(submodule, shape)
    }

    override val inSlots: Int = module.shape.inlets.size

    override def offsetOfModule(out: OutPort): Int = 0
    override def offsetOf(in: InPort): Int = in.id
    override def isComplete: Boolean = false

    override def wire(out: OutPort, in: InPort): TraversalBuilder = {
      assign(out, offsetOf(in) - offsetOfModule(out))
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      val newOutToSlot = java.util.Arrays.copyOf(outToSlot, outToSlot.length)
      newOutToSlot(out.id) = relativeSlot
      val newUnwiredOuts = unwiredOuts - 1
      if (newUnwiredOuts == 0) {
        CompletedTraversalBuilder(
          traversal = Some(MaterializeAtomic(module, newOutToSlot)),
          inSlots = inSlots,
          inToOffset = module.shape.inlets.map(in ⇒ in → in.id).toMap
        )
      } else copy(outToSlot = newOutToSlot, unwiredOuts = newUnwiredOuts)
    }

  }

  final case class LinearTraversalBuilder(
    inPort:               Option[InPort],
    outPort:              Option[OutPort],
    override val inSlots: Int,
    traversalSoFar:       Traversal
  ) extends TraversalBuilder {

    override def traversal: Option[Traversal] = Some(traversalSoFar)

    override def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      val shape = AmorphousShape(
        inPort.toList.asInstanceOf[List[Inlet[_]]],
        outPort.toList.asInstanceOf[List[Outlet[_]]]
      )

      // Generic composition is solved by the Composite builder
      CompositeTraversalBuilder().add(this, shape).add(submodule, shape)
    }

    private def rewireLastOutTo(relativeOffset: Int): LinearTraversalBuilder = {
      var unzipped: List[Traversal] = Nil
      var current: Traversal = traversalSoFar

      while (current.isInstanceOf[Concat]) {
        unzipped = current.asInstanceOf[Concat].next :: unzipped
        current = current.asInstanceOf[Concat].first
      }

      val mod = current.asInstanceOf[MaterializeAtomic].copy(outToSlots = Array(relativeOffset))

      val newTraversal = unzipped.iterator.fold(mod)(_.concat(_))

      println(newTraversal)

      copy(traversalSoFar = newTraversal)
    }

    override def wire(out: OutPort, in: InPort): TraversalBuilder = {
      if (outPort.contains(out) && inPort.contains(in))
        rewireLastOutTo(inSlots - 1).copy(inPort = None, outPort = None)
      else
        throw new IllegalArgumentException(s"The ports $in and $out cannot be accessed in this builder.")
    }

    override def offsetOfModule(out: OutPort): Int =
      if (outPort.contains(out)) 0
      else
        throw new IllegalArgumentException(s"Port $out cannot be accessed in this builder")

    override def offsetOf(in: InPort): Int = {
      if (inPort.contains(in)) inSlots - 1
      else
        throw new IllegalArgumentException(s"Port $in cannot be accessed in this builder")
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      if (outPort.contains(out))
        rewireLastOutTo(relativeSlot).copy(outPort = None)
      else
        throw new IllegalArgumentException(s"Port $out cannot be assigned in this builder")
    }

    override def isComplete: Boolean = inPort.isEmpty && outPort.isEmpty

    override def unwiredOuts: Int = if (outPort.isDefined) 1 else 0

    def append(toAppend: TraversalBuilder, shape: Shape): LinearTraversalBuilder = {
      // We don't really need the Shape for the linear append, but it is nicer to keep the API uniform here
      toAppend match {
        case otherLinear: LinearTraversalBuilder ⇒
          require(otherLinear.inPort.isDefined, "Appended linear module must have an unwired input port.")
          copy(
            outPort = otherLinear.outPort,
            inSlots = inSlots + otherLinear.inSlots,
            // Build in reverse so it yields a more efficient layout for left-to-right building
            traversalSoFar = otherLinear.traversalSoFar.concat(this.traversalSoFar)
          )

        case other ⇒
          require(shape.inlets.size == 1, "Module has not exactly one input port, it cannot be used as linear")
          require(shape.outlets.size <= 1, "Module has more than one output port, it cannot be used as linear")

          val in = shape.inlets.head
          val outOpt = shape.outlets.headOption

          val additionalTraversal: Traversal = outOpt match {
            case Some(out) ⇒ other.assign(out, -other.inSlots).traversal.get
            case None      ⇒ other.traversal.get
          }

          rewireLastOutTo(inSlots + other.offsetOf(in)).copy(
            outPort = outOpt,
            inSlots = inSlots + other.inSlots,
            traversalSoFar = additionalTraversal.concat(this.traversalSoFar)
          )
      }
    }

  }

  class BuilderKey {
    override def toString = s"K:$hashCode"
  }

  final case class CompositeTraversalBuilder(
    reverseTraversal:   List[BuilderKey]                  = Nil,
    inSlots:            Int                               = 0,
    inOffsets:          Map[InPort, Int]                  = Map.empty,
    inBaseOffsetForOut: Map[OutPort, Int]                 = Map.empty,
    pendingBuilders:    Map[BuilderKey, TraversalBuilder] = Map.empty,
    outOwners:          Map[OutPort, BuilderKey]          = Map.empty,
    unwiredOuts:        Int                               = 0
  ) extends TraversalBuilder {

    override def toString: String =
      s"""
         |CompositeTraversal(
         |  reverseTraversal = $reverseTraversal
         |  inSlots = $inSlots
         |  inOffsets = $inOffsets
         |  inBaseOffsetForOut = $inBaseOffsetForOut
         |  outOwners = $outOwners
         |  unwiredOuts = $unwiredOuts
         |)
       """.stripMargin

    override def offsetOfModule(out: OutPort): Int = inBaseOffsetForOut(out)
    override def offsetOf(in: InPort): Int = inOffsets(in)
    override def isComplete = false

    def completeIfPossible: TraversalBuilder = {
      if (unwiredOuts == 0) {
        var traversal: Traversal = EmptyTraversal
        var remaining = reverseTraversal
        while (remaining.nonEmpty) {
          traversal = pendingBuilders(remaining.head).traversal.get.concat(traversal)
          remaining = remaining.tail
        }
        CompletedTraversalBuilder(
          traversal = Some(traversal),
          inSlots,
          inOffsets
        )
      } else this
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      // Which module out belongs to
      val builderKey = outOwners(out)
      val submodule = pendingBuilders(builderKey)

      val result = submodule.assign(out.mappedTo, relativeSlot)
      val wired = if (result.isComplete) {
        // Remove the builder (and associated data), and append its traversal

        copy(
          inBaseOffsetForOut = inBaseOffsetForOut - out,
          outOwners = outOwners - out,
          pendingBuilders = pendingBuilders.updated(builderKey, result),
          // pendingBuilders = pendingBuilders - builderKey,
          unwiredOuts = unwiredOuts - 1
        )
      } else {
        // Update structures with result
        copy(
          unwiredOuts = unwiredOuts - 1,
          pendingBuilders = pendingBuilders.updated(builderKey, result)
        )
      }

      wired.completeIfPossible
    }

    // Requires that a remapped Shape's ports contain the same ID as their target ports!
    def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      val builderKey = new BuilderKey

      val added = if (submodule.isComplete) {
        var newInOffsets = inOffsets
        val inIterator = shape.inlets.iterator
        while (inIterator.hasNext) {
          val in = inIterator.next()
          // Calculate offset in the current scope
          newInOffsets = newInOffsets.updated(in, inSlots + submodule.offsetOf(in.mappedTo))
        }

        copy(
          reverseTraversal = builderKey :: reverseTraversal,
          inSlots = inSlots + submodule.inSlots,
          pendingBuilders = pendingBuilders.updated(builderKey, submodule),
          inOffsets = newInOffsets
        )
      } else {
        var newInOffsets = inOffsets
        var newOutOffsets = inBaseOffsetForOut
        var newOutOwners = outOwners

        val inIterator = shape.inlets.iterator
        while (inIterator.hasNext) {
          val in = inIterator.next()
          // Calculate offset in the current scope
          newInOffsets = newInOffsets.updated(in, inSlots + submodule.offsetOf(in.mappedTo))
        }

        val outIterator = shape.outlets.iterator
        while (outIterator.hasNext) {
          val out = outIterator.next()
          newOutOffsets = newOutOffsets.updated(out, inSlots + submodule.offsetOfModule(out.mappedTo))
          newOutOwners = newOutOwners.updated(out, builderKey)
        }

        copy(
          reverseTraversal = builderKey :: reverseTraversal,
          inSlots = inSlots + submodule.inSlots,
          inOffsets = newInOffsets,
          inBaseOffsetForOut = newOutOffsets,
          outOwners = newOutOwners,
          pendingBuilders = pendingBuilders.updated(builderKey, submodule),
          unwiredOuts = unwiredOuts + submodule.unwiredOuts
        )
      }

      added.completeIfPossible
    }

    def wire(out: OutPort, in: InPort): TraversalBuilder = {
      copy(inOffsets = inOffsets - in).assign(out, offsetOf(in) - offsetOfModule(out))
    }

  }

}
