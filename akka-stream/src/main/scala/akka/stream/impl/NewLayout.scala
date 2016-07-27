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

  trait TraversalBuilder {

    def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder

    def wire(out: OutPort, in: InPort): TraversalBuilder

    def offsetOf(out: OutPort): Int

    def offsetOf(in: InPort): Int

    def assign(out: OutPort, relativeSlot: Int): TraversalBuilder

    final def isComplete: Boolean = unwiredOuts == 0

    def inSlots: Int
    def outSlots: Int

    def traversal: Traversal

    def unwiredOuts: Int
  }

  final case class AtomicTraversalBuilder(module: AtomicModule, outToSlot: Array[Int], unwiredOuts: Int) extends TraversalBuilder {

    override def traversal: Traversal = MaterializeAtomic(module, outToSlot)

    override def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      CompositeTraversalBuilder().add(this, module.shape).add(submodule, shape)
    }

    override val inSlots: Int = module.shape.inlets.size
    override val outSlots: Int = module.shape.outlets.size

    override def offsetOf(out: OutPort): Int = out.id
    override def offsetOf(in: InPort): Int = in.id

    // Initialize port IDs
    {
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
    }

    override def wire(out: OutPort, in: InPort): TraversalBuilder = {
      // TODO: Check that ports really belong
      assign(out, offsetOf(in) - offsetOf(out))
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      val newOutToSlot = java.util.Arrays.copyOf(outToSlot, outToSlot.length)
      newOutToSlot(out.id) = relativeSlot
      copy(outToSlot = newOutToSlot, unwiredOuts = unwiredOuts - 1)
    }

  }

  class BuilderKey {
    override def toString = s"K:$hashCode"
  }

  final case class CompositeTraversalBuilder(
    reverseTraversal: List[BuilderKey]                  = Nil,
    inSlots:          Int                               = 0,
    outSlots:         Int                               = 0,
    inOffsets:        Map[InPort, Int]                  = Map.empty,
    outOffsets:       Map[OutPort, Int]                 = Map.empty,
    pendingBuilders:  Map[BuilderKey, TraversalBuilder] = Map.empty,
    outOwners:        Map[OutPort, BuilderKey]          = Map.empty,
    unwiredOuts:      Int                               = 0
  ) extends TraversalBuilder {

    override def toString: String =
      s"""
         |CompositeTraversal(
         |  reverseTraversal = $reverseTraversal
         |  inSlots = $inSlots
         |  outSlots = $outSlots
         |  inOffsets = $inOffsets
         |  outOffsets = $outOffsets
         |  outOwners = $outOwners
         |  unwiredOuts = $unwiredOuts
         |)
       """.stripMargin

    override def offsetOf(out: OutPort): Int = outOffsets(out)
    override def offsetOf(in: InPort): Int = inOffsets(in)

    private[this] var _cachedTraversal: Traversal = _

    // Only call if module is completed!!
    override def traversal: Traversal = {
      if (_cachedTraversal ne null) _cachedTraversal
      else {
        var result: Traversal = EmptyTraversal
        var remaining = reverseTraversal
        while (remaining.nonEmpty) {
          result = pendingBuilders(remaining.head).traversal.concat(result)
          remaining = remaining.tail
        }
        _cachedTraversal = result
        result
      }
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      // Which module out belongs to
      val builderKey = outOwners(out)
      val submodule = pendingBuilders(builderKey)

      val result = submodule.assign(out.mappedTo, relativeSlot)
      if (result.isComplete) {
        // Remove the builder (and associated data), and append its traversal

        copy(
          outOffsets = outOffsets - out,
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
    }

    // Requires that a remapped Shape's ports contain the same ID as their target ports!
    def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      val builderKey = new BuilderKey

      if (submodule.isComplete) {
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
          outSlots = outSlots + submodule.outSlots,
          pendingBuilders = pendingBuilders.updated(builderKey, submodule),
          inOffsets = newInOffsets
        )
      } else {
        var newInOffsets = inOffsets
        var newOutOffsets = outOffsets
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
          newOutOffsets = newOutOffsets.updated(out, outSlots + submodule.offsetOf(out.mappedTo))
          newOutOwners = newOutOwners.updated(out, builderKey)
        }

        copy(
          reverseTraversal = builderKey :: reverseTraversal,
          inSlots = inSlots + submodule.inSlots,
          outSlots = outSlots + submodule.outSlots,
          inOffsets = newInOffsets,
          outOffsets = newOutOffsets,
          outOwners = newOutOwners,
          pendingBuilders = pendingBuilders.updated(builderKey, submodule),
          unwiredOuts = unwiredOuts + submodule.unwiredOuts
        )
      }
    }

    def wire(out: OutPort, in: InPort): TraversalBuilder = {
      copy(inOffsets = inOffsets - in).assign(out, offsetOf(in) - offsetOf(out))
    }

  }

}
