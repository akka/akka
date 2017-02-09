/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream._
import akka.stream.impl.StreamLayout.{ AtomicModule, Module }
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource

/**
 * INTERNAL API
 */
private[akka] object NewLayout {

  // Materialized values
  // Attributes
  // MatValueSource
  // Fusing

  /*
   * TODO: Optimizations
   * Two approaches are likely to give most gains:
   *  - replace immutable.Map with something cheaper and better suited for small maps (all maps we have have
   *    sizes proportional to unwired ports which are usually few. One likely implementation can be a "map" backed
   *    by a simple array with a linear scan as lookup and copy-on-write for updates.
   *  - make the Traversal a rope. Materialization is likely constrained by the pointer chasing right now, I would
   *    expect making the Concat to be copy-on-write array backed instead (linking to the next chunk once a threshold
   *    is reached) is probably the way to go.
   */

  /**
   * Graphs to be materialized are defined by their traversal. There is no explicit graph information tracked, instead
   * a sequence of steps required to "reconstruct" the graph.
   *
   * "Reconstructing" a graph here has a very clear-cut definition: assign a gapless range of integers from
   * 0..connectionCount to inputs and outputs of modules, so that those that are wired together receive the same
   * number (and those which are not receive different numbers). This feature can be used to
   *  - materialize a graph, using the slots as indices to an array of Publishers/Subscribers that need to be wired
   *    together
   *  - fuse a graph, using the slots to construct a [[akka.stream.impl.fusing.GraphInterpreter.GraphAssembly]] which
   *    uses a similar layout
   *  - create a DOT formatted output for visualization
   *  - convert the graph to another data structure
   *
   * The Traversal is designed to be position independent so that multiple traversals can be composed relatively
   * simply. This particular feature also avoids issues with multiply imported modules where the identity must
   * be encoded somehow. The two imports don't need any special treatment as they are at different positions in
   * the traversal. See [[MaterializeAtomic]] for more details.
   */
  sealed trait Traversal {

    /**
     * Gives the next step of the traversal.
     */
    def next: Traversal

    /**
     * Concatenates two traversals building a new Traversal which traverses both.
     */
    def concat(that: Traversal): Traversal = {
      if (that eq EmptyTraversal) this
      else Concat(this, that)
    }
  }

  /**
   * A Traversal that consists of two traversals. The linked traversals must be traversed in first, next order.
   */
  final case class Concat(first: Traversal, next: Traversal) extends Traversal

  /**
   * Arriving at this step means that an atomic module needs to be materialized (or any other activity which
   * assigns something to wired output-input port pairs).
   *
   * The traversing party must assign port numbers in the following way:
   *  - input ports are implicitly assigned to numbers. Every module's input ports are assigned to consecutive numbers
   *    according to their order in the shape. In other words, the materializer only needs to keep a counter and
   *    increment it for every visited input port.
   *  - the assigned number of the first input port for every module should be saved while materializing the module.
   *    every output port should be assigned to (base + outToSlots(out.id)) where base is the number of the first
   *    input port of the module (or the last unused input number if it has no input ports) and outToSlots is the
   *    array provided by the traversal step.
   *
   * Since the above two rules always require local only computations (except a counter) this achieves
   * positional independence of materializations.
   *
   * See [[TraversalTestUtils]] class and the `testMaterialize` method for a simple example.
   */
  final case class MaterializeAtomic(module: Module, outToSlots: Array[Int]) extends Traversal {
    override def next: Traversal = EmptyTraversal

    override def toString: String = s"MaterializeAtomic($module, ${outToSlots.mkString("[", ", ", "]")})"
  }

  /**
   * Traversal with no steps.
   */
  object EmptyTraversal extends Traversal {
    def next: Traversal =
      throw new IllegalStateException("EmptyTraversal has no next Traversal but next was called.")

    override def concat(that: Traversal): Traversal = that
  }

  // TODO: Replace with attribute?
  final case class AsyncBoundary(next: Traversal) extends Traversal

  // Below classes are not yet used !!!
  final case class PushMaterializedValue(next: Traversal) extends Traversal
  final case class PopMaterializedValue(next: Traversal) extends Traversal
  final case class TapMaterializedValue(src: MaterializedValueSource[Any], next: Traversal) extends Traversal
  final case class AddAttributes(attributes: Attributes, next: Traversal) extends Traversal

  /**
   * Assign ports their id, which is their position inside the Shape. This is used both by the GraphInterpreter
   * and the layout system here.
   */
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

    /**
     * Create a generic traversal builder starting from an atomic module.
     */
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

    private[this] val wireBackward: Array[Int] = Array(-1)
    private[this] val noWire: Array[Int] = Array()

    /**
     * Create a traversal builder specialized for linear graphs. This is designed to be much faster and lightweight
     * than its generic counterpart. It can be freely mixed with the generic builder in both ways.
     */
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

  /**
   * A builder for a Traversal. The purpose of subclasses of this trait is to eventually build a Traversal that
   * describes the graph. Depending on whether the graph is linear or generic different approaches can be used but
   * they still result in a Traversal.
   *
   * The resulting Traversal can be accessed via the `traversal` method once the graph is completed (all ports are
   * wired). The Traversal may be accessed earlier, depending on the type of the builder and certain conditions.
   * See [[CompositeTraversalBuilder]] and [[LinearTraversalBuilder]].
   */
  sealed trait TraversalBuilder {

    /**
     * Adds a module to the builder. It is possible to add a module with a different Shape (import), in this
     * case the ports of the shape MUST have their `mappedTo` field pointing to the original ports. The act of being
     * imported will not be reflected in the final Traversal, the Shape is only used by the builder to disambiguate
     * between multiple imported instances of the same module.
     *
     * See append in the [[LinearTraversalBuilder]] for a more efficient alternative for linear graphs.
     */
    def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder

    /**
     * Connects two unwired ports in the graph. For imported modules, use the ports of their "import shape". These
     * ports must have their `mappedTo` field set and point to the original ports.
     *
     * See append in the [[LinearTraversalBuilder]] for a more efficient alternative for linear graphs.
     */
    def wire(out: OutPort, in: InPort): TraversalBuilder

    /**
     * Returns the base offset (the first number an input port would receive if there is any) of the module to which
     * the port belongs *relative to this builder*. This is used to calculate the relative offset of output port mappings
     * (see [[MaterializeAtomic]]).
     *
     * This method only guarantees to return the offset of modules for output ports that have not been wired.
     */
    def offsetOfModule(out: OutPort): Int

    /**
     * Returns the number assigned to a certain input port *relative* to this module.
     *
     * This method only guarantees to return the offset of input ports that have not been wired.
     */
    def offsetOf(in: InPort): Int

    /**
     * Finish the wiring of an output port to an input port by assigning the relative slot for the output port.
     *
     * (see [[MaterializeAtomic]] for details of the resolution process)
     */
    def assign(out: OutPort, relativeSlot: Int): TraversalBuilder

    /**
     * Returns true if the Traversal is available. Not all builders are able to build up the Traversal incrementally.
     * Generally a traversal is complete if there are no unwired output ports.
     */
    def isTraversalComplete: Boolean

    /**
     * The total number of input ports encountered so far. Gives the first slot to which a new input port can be
     * assigned (if a new module is added).
     */
    def inSlots: Int

    /**
     * Returns the Traversal if ready for this (sub)graph.
     */
    def traversal: Option[Traversal] = None

    /**
     * The number of output ports that have not been wired.
     */
    def unwiredOuts: Int
  }

  /**
   * Returned by [[CompositeTraversalBuilder]] once all output ports of a subgraph has been wired.
   */
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

    override def isTraversalComplete: Boolean = true

    override def wire(out: OutPort, in: InPort): TraversalBuilder =
      throw new UnsupportedOperationException("Cannot wire ports in a completed builder.")

    override def unwiredOuts: Int = 0

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder =
      throw new UnsupportedOperationException("Cannot assign ports to slots in a completed builder.")

    override def offsetOfModule(out: OutPort): Int =
      throw new UnsupportedOperationException("Cannot look up offsets in a completed builder.")

  }

  /**
   * Represents a builder that contains a single atomic module. Its primary purpose is to track and build the
   * outToSlot array which will be then embedded in a [[MaterializeAtomic]] Traversal step.
   */
  final case class AtomicTraversalBuilder(module: AtomicModule, outToSlot: Array[Int], unwiredOuts: Int) extends TraversalBuilder {

    override def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      // TODO: Use automatically a linear builder if applicable
      // Create a composite, add ourselves, then the other.
      CompositeTraversalBuilder().add(this, module.shape).add(submodule, shape)
    }

    override val inSlots: Int = module.shape.inlets.size

    override def offsetOfModule(out: OutPort): Int = 0
    override def offsetOf(in: InPort): Int = in.id
    override def isTraversalComplete: Boolean = false

    override def wire(out: OutPort, in: InPort): TraversalBuilder = {
      assign(out, offsetOf(in) - offsetOfModule(out))
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      // Create a new array, with the output port assigned to its relative slot
      val newOutToSlot = java.util.Arrays.copyOf(outToSlot, outToSlot.length)
      newOutToSlot(out.id) = relativeSlot

      // Check if every output port has been assigned, if yes, we have a Traversal for this module.
      val newUnwiredOuts = unwiredOuts - 1
      if (newUnwiredOuts == 0) {
        CompletedTraversalBuilder(
          traversal = Some(MaterializeAtomic(module, newOutToSlot)),
          inSlots = inSlots,
          // TODO Optimize Map creation
          inToOffset = module.shape.inlets.iterator.map(in ⇒ in → in.id).toMap
        )
      } else copy(outToSlot = newOutToSlot, unwiredOuts = newUnwiredOuts)
    }

  }

  /**
   * Traversal builder that is optimized for linear graphs (those that contain modules with at most one input and
   * at most one output port). The Traversal is simply built up in reverse order and output ports are automatically
   * assigned to -1 due to the nature of the graph. The only exception is when composites created by
   * [[CompositeTraversalBuilder]] are embedded. These are not guaranteed to have their unwired input/output ports
   * in a fixed location, therefore the last step of the Traversal might need to be changed in those cases from the
   * -1 relative offset to something else (see rewireLastOutTo).
   */
  final case class LinearTraversalBuilder(
    inPort:               Option[InPort],
    outPort:              Option[OutPort],
    override val inSlots: Int,
    traversalSoFar:       Traversal
  ) extends TraversalBuilder {

    /**
     * This builder can always return a traversal.
     */
    override def traversal: Option[Traversal] = Some(traversalSoFar)

    override def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      // We will need a shape, and an Amorphous is good enough
      val shape = AmorphousShape(
        inPort.toList.asInstanceOf[List[Inlet[_]]],
        outPort.toList.asInstanceOf[List[Outlet[_]]]
      )

      // Generic composition is solved by the Composite builder
      CompositeTraversalBuilder().add(this, shape).add(submodule, shape)
    }

    /**
     * In case the default relative wiring of -1 is not applicable (due to for example an embedded composite
     * [[CompositeTraversalBuilder]] created traversal) we need to change the mapping for the module we added
     * last. This method tears down the traversal until it finds that [[MaterializeAtomic]], changes the mapping,
     * then rebuilds the Traversal.
     */
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

    /**
     * Since this is a linear traversal, this should not be called in most of the cases. The only notable
     * exception is when a Flow is wired to itself.
     */
    override def wire(out: OutPort, in: InPort): TraversalBuilder = {
      if (outPort.contains(out) && inPort.contains(in))
        rewireLastOutTo(inSlots - 1).copy(inPort = None, outPort = None)
      else
        throw new IllegalArgumentException(s"The ports $in and $out cannot be accessed in this builder.")
    }

    override def offsetOfModule(out: OutPort): Int =
      if (outPort.contains(out)) 0 // Output belongs to the last module, which will be materialized *first*
      else
        throw new IllegalArgumentException(s"Port $out cannot be accessed in this builder")

    override def offsetOf(in: InPort): Int = {
      if (inPort.contains(in)) inSlots - 1 // Input belongs to the first module, which will be materialized *last*
      else
        throw new IllegalArgumentException(s"Port $in cannot be accessed in this builder")
    }

    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      if (outPort.contains(out))
        rewireLastOutTo(relativeSlot).copy(outPort = None) // Rewrite the traversal and remove the wired port
      else
        throw new IllegalArgumentException(s"Port $out cannot be assigned in this builder")
    }

    override def isTraversalComplete: Boolean = outPort.isEmpty

    override def unwiredOuts: Int = if (outPort.isDefined) 1 else 0

    // We don't really need the Shape for the linear append, but it is nicer to keep the API uniform here
    /**
     * Append any builder that is linear shaped (have at most one input and at most one output port) to the
     * end of this graph, connecting the output of the last module to the input of the appended module.
     */
    def append(toAppend: TraversalBuilder, shape: Shape): LinearTraversalBuilder = {
      toAppend match {
        case otherLinear: LinearTraversalBuilder ⇒
          require(otherLinear.inPort.isDefined, "Appended linear module must have an unwired input port.")

          // Simply just take the new unwired ports, increase the number of inSlots and concatenate the traversals
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

          // We MUST assign the last output port of the composite builder otherwise we will not get the Traversal!
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

  /**
   * Helper class that is only used to identify a [[TraversalBuilder]] in a [[CompositeTraversalBuilder]]. The
   * reason why this is needed is because the builder is referenced at various places, while it needs to be mutated.
   * In an immutable data structure this is best done with an indirection, i.e. places refer to this immutable key and
   * look up the current state in an extra Map.
   */
  class BuilderKey {
    override def toString = s"K:$hashCode"
  }

  /**
   * A generic builder that builds a traversal for graphs of arbitrary shape. The memory retained by this class
   * usually decreases as ports are wired since auxiliary data is only maintained for ports that are unwired.
   *
   * This builder MUST construct its Traversal in the *exact* same order as its modules were added, since the first
   * (and subsequent) input port of a module is implicitly assigned by its traversal order. Emitting Traversal nodes
   * in a non-deterministic order (depending on wiring order) would mess up all relative addressing. This is the
   * primary technical reason why a reverseTraversal list is maintained and the Traversal can only be completed once
   * all output ports have been wired.
   *
   * @param reverseTraversal Keeps track of traversal steps that needs to be concatenated. This is basically
   *                         a "queue" of BuilderKeys that point to builders of submodules/subgraphs. Since it is
   *                         unknown in which order will the submodules "complete" (have all of their outputs assigned)
   *                         we delay the creation of the actual Traversal.
   * @param inSlots          The number of input ports this graph has in total.
   * @param inOffsets        Map to look up the offset of input ports not yet wired
   * @param inBaseOffsetForOut Map to look up the base (input) offset of a module that owns the given output port
   * @param pendingBuilders  Map to contain the "mutable" builders referred by BuilderKeys
   * @param outOwners        Map of output ports to their parent builders (actually the BuilderKey)
   * @param unwiredOuts      Number of output ports that have not yet been wired/assigned
   */
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
    override def isTraversalComplete = false

    /**
     * Convert this builder to a [[CompositeTraversalBuilder]] if there are no more unwired outputs.
     */
    def completeIfPossible: TraversalBuilder = {
      if (unwiredOuts == 0) {
        var traversal: Traversal = EmptyTraversal
        var remaining = reverseTraversal
        while (remaining.nonEmpty) {
          // At this point all the builders we have are completed and we can finally build our traversal
          traversal = pendingBuilders(remaining.head).traversal.get.concat(traversal)
          remaining = remaining.tail
        }
        // The CompleteTraversalBuilder only keeps the minimum amount of necessary information that is needed for it
        // to be embedded in a larger graph, making partial graph reuse much more efficient.
        CompletedTraversalBuilder(
          traversal = Some(traversal),
          inSlots,
          inOffsets
        )
      } else this
    }

    /**
     * Assign an output port a relative slot (relative to the base input slot of its module, see [[MaterializeAtomic]])
     */
    override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
      // Which module out belongs to (indirection via BuilderKey and pendingBuilders)
      val builderKey = outOwners(out)
      val submodule = pendingBuilders(builderKey)

      // Do the assignment in the submodule
      val result = submodule.assign(out.mappedTo, relativeSlot)
      val wired = if (result.isTraversalComplete) {
        // Remove the builder (and associated data).
        // We can't simply append its Traversal as there might be uncompleted builders that come earlier in the
        // final traversal (remember, input ports are assigned in traversal order of modules, and the inOffsets
        // and inBaseOffseForOut Maps are updated when adding a module; we must respect addition order).

        copy(
          inBaseOffsetForOut = inBaseOffsetForOut - out,
          outOwners = outOwners - out,
          // TODO Optimize Map access
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

      // If we have no more unconnected outputs, we can finally build the Traversal and shed most of the auxiliary data.
      wired.completeIfPossible
    }

    // Requires that a remapped Shape's ports contain the same ID as their target ports!
    def add(submodule: TraversalBuilder, shape: Shape): TraversalBuilder = {
      val builderKey = new BuilderKey

      val added = if (submodule.isTraversalComplete) {
        // We only need to keep track of the offsets of unwired inputs. Outputs have all been wired
        // (isTraversalComplete = true).

        var newInOffsets = inOffsets
        val inIterator = shape.inlets.iterator
        while (inIterator.hasNext) {
          val in = inIterator.next()
          // Calculate offset in the current scope. This is the our first unused input slot plus
          // the relative offset of the input port in the submodule.
          // TODO Optimize Map access
          newInOffsets = newInOffsets.updated(in, inSlots + submodule.offsetOf(in.mappedTo))
        }

        copy(
          reverseTraversal = builderKey :: reverseTraversal,
          inSlots = inSlots + submodule.inSlots,
          pendingBuilders = pendingBuilders.updated(builderKey, submodule),
          inOffsets = newInOffsets
        )
      } else {
        // Added module have unwired outputs.

        var newInOffsets = inOffsets
        var newBaseOffsetsForOut = inBaseOffsetForOut
        var newOutOwners = outOwners

        // See the other if case for explanation of this
        val inIterator = shape.inlets.iterator
        while (inIterator.hasNext) {
          val in = inIterator.next()
          // Calculate offset in the current scope
          // TODO Optimize Map access
          newInOffsets = newInOffsets.updated(in, inSlots + submodule.offsetOf(in.mappedTo))
        }

        val outIterator = shape.outlets.iterator
        while (outIterator.hasNext) {
          val out = outIterator.next()
          // Record the base offsets of all the modules we included and which have unwired output ports. We need
          // to adjust their offset by inSlots as that would be their new position in this module.
          newBaseOffsetsForOut = newBaseOffsetsForOut.updated(out, inSlots + submodule.offsetOfModule(out.mappedTo))
          // TODO Optimize Map access
          newOutOwners = newOutOwners.updated(out, builderKey)
        }

        copy(
          reverseTraversal = builderKey :: reverseTraversal,
          inSlots = inSlots + submodule.inSlots,
          inOffsets = newInOffsets,
          inBaseOffsetForOut = newBaseOffsetsForOut,
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
