/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.impl.StreamLayout.{ AtomicModule, Module }
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.{ Shape, OutPort, InPort, Attributes }

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
  }

  object EmptyTraversal extends Traversal {
    def next: Traversal =
      throw new IllegalStateException("EmptyTraversal has no next Traversal but next was called.")

    override def concat(that: Traversal): Traversal = that
  }

  object TraversalBuilder {
    def atomic(module: AtomicModule): TraversalBuilder = {
      // Initialize port IDs
      val inIter = module.shape.inlets.iterator
      var i = 0
      while (inIter.hasNext) {
        inIter.next.id = i
        i += 1
      }

      val outIter = module.shape.outlets.iterator
      i = 0
      while (outIter.hasNext) {
        outIter.next.id = i
        i += 1
      }

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
