/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.{ util ⇒ ju }
import java.util.Arrays
import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scala.annotation.tailrec
import akka.stream._
import akka.stream.Attributes.AsyncBoundary
import akka.stream.Fusing.{ FusedGraph, StructuralInfo }
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.impl.StreamLayout
import akka.stream.impl.StreamLayout._
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource

/**
 * INTERNAL API
 */
private[stream] object Fusing {

  final val Debug = false

  /**
   * Fuse everything that is not forbidden via AsyncBoundary attribute.
   */
  def aggressive[S <: Shape, M](g: Graph[S, M]): FusedGraph[S, M] = {
    val struct = new BuildStructuralInfo
    /*
     * First perform normalization by descending the module tree and recording
     * information in the BuildStructuralInfo instance.
     */
    val matValue =
      try descend(g.module, Attributes.none, struct, struct.newGroup(""), "")
      catch {
        case NonFatal(ex) ⇒
          if (Debug) struct.dump()
          throw ex
      }
    /*
     * Then create a copy of the original Shape with the new copied ports.
     */
    val shape = g.shape.copyFromPorts(
      struct.newInlets(g.shape.inlets),
      struct.newOutlets(g.shape.outlets)).asInstanceOf[S]
    /*
     * Extract the full topological information from the builder before
     * removing assembly-internal (fused) wirings in the next step.
     */
    val info = struct.toInfo
    /*
     * Perform the fusing of `struct.groups` into GraphModules (leaving them
     * as they are for non-fusable modules).
     */
    struct.breakUpGroupsByDispatcher()
    val modules = fuse(struct)
    /*
     * Now we have everything ready for a FusedModule.
     */
    val module = FusedModule(
      modules,
      shape,
      immutable.Map.empty ++ struct.downstreams.asScala,
      immutable.Map.empty ++ struct.upstreams.asScala,
      matValue,
      Attributes.none,
      info)

    if (StreamLayout.Debug) validate(module)
    if (Debug) println(module)

    FusedGraph(module, shape)
  }

  /**
   * Take the fusable islands identified by `descend` in the `groups` list
   * and execute their fusion; only fusable islands will have multiple modules
   * in their set.
   */
  private def fuse(struct: BuildStructuralInfo): Set[Module] =
    struct.groups.asScala.flatMap { group ⇒
      if (group.size == 0) Nil
      else if (group.size == 1) group.iterator.next() :: Nil
      else fuseGroup(struct, group) :: Nil
    }(collection.breakOut)

  /**
   * Transform a set of GraphStageModules into a single GraphModule. This is done
   * by performing a traversal of all their Inlets, sorting them into those without
   * internal connections (the exposed inlets) and those with internal connections
   * (where the corresponding Outlet is recorded in a map so that it will be wired
   * to the same slot number in the GraphAssembly). Then all Outlets are traversed,
   * completing internal connections using the aforementioned maps and appending
   * the others to the list of exposed Outlets.
   */
  private def fuseGroup(struct: BuildStructuralInfo, group: ju.Set[Module]): GraphModule = {
    val stages = new Array[GraphStageWithMaterializedValue[Shape, Any]](group.size)
    val matValIDs = new Array[Module](group.size)
    val attributes = new Array[Attributes](group.size)

    /*
     * The overall GraphAssembly arrays are constructed in three parts:
     * - 1) exposed inputs (ins)
     * - 2) connections (ins and outs)
     * - 3) exposed outputs (outs)
     */
    val insB1, insB2 = new ju.ArrayList[Inlet[_]]
    val outsB3 = new ju.ArrayList[Outlet[_]]
    val inOwnersB1, inOwnersB2 = new ju.ArrayList[Int]
    val outOwnersB3 = new ju.ArrayList[Int]

    // for the shape of the GraphModule
    val inlets = new ju.ArrayList[Inlet[_]]
    val outlets = new ju.ArrayList[Outlet[_]]

    // connection slots are allocated from the inputs side, outs find their place by this map
    val outConns: ju.Map[OutPort, Int] = new ju.HashMap

    /*
     * First traverse all Inlets and sort them into exposed and internal,
     * taking note of their partner Outlets where appropriate.
     */
    var pos = 0
    var it = group.iterator
    val ups = struct.upstreams
    val downs = struct.downstreams
    val outGroup = struct.outGroup
    while (it.hasNext) it.next() match {
      case copy @ CopiedModule(shape, attr, gsm: GraphStageModule) ⇒
        stages(pos) = gsm.stage
        matValIDs(pos) = copy
        attributes(pos) = attr and gsm.attributes

        shape.inlets.iterator.zip(gsm.shape.inlets.iterator).foreach {
          case (in, orig) ⇒
            val out = ups.get(in)
            val internal = (out != null) && (outGroup.get(out) eq group)
            if (internal) {
              ups.remove(in)
              downs.remove(out)
              outConns.put(out, insB2.size)
              insB2.add(orig)
              inOwnersB2.add(pos)
            } else {
              insB1.add(orig)
              inOwnersB1.add(pos)
              inlets.add(in)
            }
        }

        pos += 1
    }

    val outsB2 = new Array[Outlet[_]](insB2.size)
    val outOwnersB2 = new Array[Int](insB2.size)

    /*
     * Then traverse all Outlets and complete connections.
     */
    pos = 0
    it = group.iterator
    while (it.hasNext) it.next() match {
      case CopiedModule(shape, _, gsm: GraphStageModule) ⇒
        shape.outlets.iterator.zip(gsm.shape.outlets.iterator).foreach {
          case (out, orig) ⇒
            if (outConns.containsKey(out)) {
              val idx = outConns.remove(out)
              outsB2(idx) = orig
              outOwnersB2(idx) = pos
            } else {
              outsB3.add(orig)
              outOwnersB3.add(pos)
              outlets.add(out)
            }
        }
        pos += 1
    }

    /*
     * Now mechanically gather together the GraphAssembly arrays from their various pieces.
     */

    val shape = AmorphousShape(inlets.asScala.to[immutable.Seq], outlets.asScala.to[immutable.Seq])

    val connStart = insB1.size
    val conns = insB2.size
    val outStart = connStart + conns
    val size = outStart + outsB3.size

    val ins = new Array[Inlet[_]](size)
    copyToArray(insB2.iterator, ins, copyToArray(insB1.iterator, ins, 0))

    val inOwners = new Array[Int](size)
    Arrays.fill(inOwners, copyToArray(inOwnersB2.iterator, inOwners, copyToArray(inOwnersB1.iterator, inOwners, 0)), size, -1)

    val outs = new Array[Outlet[_]](size)
    System.arraycopy(outsB2, 0, outs, connStart, conns)
    copyToArray(outsB3.iterator, outs, outStart)

    val outOwners = new Array[Int](size)
    Arrays.fill(outOwners, 0, connStart, -1)
    System.arraycopy(outOwnersB2, 0, outOwners, connStart, conns)
    copyToArray(outOwnersB3.iterator, outOwners, outStart)

    // FIXME attributes should contain some naming info and async boundary where needed
    val firstModule = group.iterator.next()
    val async = if (isAsync(firstModule)) Attributes(AsyncBoundary) else Attributes.none
    val disp = dispatcher(firstModule) match {
      case None    ⇒ Attributes.none
      case Some(d) ⇒ Attributes(d)
    }
    val attr = async and disp

    GraphModule(new GraphInterpreter.GraphAssembly(stages, attributes, ins, inOwners, outs, outOwners), shape, attr, matValIDs)
  }

  @tailrec private def copyToArray[T](it: ju.Iterator[T], array: Array[T], idx: Int): Int =
    if (it.hasNext) {
      array(idx) = it.next()
      copyToArray(it, array, idx + 1)
    } else idx

  /**
   * This is a normalization step for the graph that also collects the needed
   * information for later fusing. The goal is to transform an arbitrarily deep
   * module tree into one that has exactly two levels: all direct submodules are
   * CopiedModules where each contains exactly one atomic module. This way all
   * modules have their own identity and all necessary port copies have been
   * made. The upstreams/downstreams in the BuildStructuralInfo are rewritten
   * to point to the shapes of the copied modules.
   *
   * The materialized value computation is rewritten as well in that all
   * leaf nodes point to the copied modules and all nested computations are
   * “inlined”, resulting in only one big computation tree for the whole
   * normalized overall module. The contained MaterializedValueSource stages
   * are also rewritten to point to the copied MaterializedValueNodes. This
   * correspondence is then used during materialization to trigger these sources
   * when “their” node has received its value.
   */
  private def descend(m: Module,
                      inheritedAttributes: Attributes,
                      struct: BuildStructuralInfo,
                      openGroup: ju.Set[Module],
                      indent: String): MaterializedValueNode = {
    def log(msg: String): Unit = println(indent + msg)
    val async = m match {
      case _: GraphStageModule ⇒ m.attributes.contains(AsyncBoundary)
      case _ if m.isAtomic     ⇒ true // non-GraphStage atomic or has AsyncBoundary
      case _                   ⇒ m.attributes.contains(AsyncBoundary)
    }
    if (Debug) log(s"entering ${m.getClass} (async=$async, name=${m.attributes.nameLifted}, dispatcher=${dispatcher(m)})")
    val localGroup =
      if (async) struct.newGroup(indent)
      else openGroup

    if (m.isAtomic) {
      if (Debug) log(s"atomic module $m")
      struct.addModule(m, localGroup, inheritedAttributes, indent)
    } else {
      val attributes = inheritedAttributes and m.attributes
      m match {
        case CopiedModule(shape, _, copyOf) ⇒
          val ret = descend(copyOf, attributes, struct, localGroup, indent + "  ")
          struct.rewire(copyOf.shape, shape, indent)
          ret
        case _ ⇒
          // we need to keep track of all MaterializedValueSource nodes that get pushed into the current
          // computation context (i.e. that need the same value).
          struct.enterMatCtx()
          // now descend into submodules and collect their computations (plus updates to `struct`)
          val subMat: Predef.Map[Module, MaterializedValueNode] =
            m.subModules.map(sub ⇒ sub -> descend(sub, attributes, struct, localGroup, indent + "  "))(collection.breakOut)
          // we need to remove all wirings that this module copied from nested modules so that we
          // don’t do wirings twice
          val down = m.subModules.foldLeft(m.downstreams.toSet)((set, m) ⇒ set -- m.downstreams)
          down.foreach {
            case (start, end) ⇒ struct.wire(start, end, indent)
          }
          // now rewrite the materialized value computation based on the copied modules and their computation nodes
          val newMat = rewriteMat(subMat, m.materializedValueComputation)
          // and finally rewire all MaterializedValueSources to their new computation nodes
          val matSrcs = struct.exitMatCtx()
          matSrcs.foreach { c ⇒
            if (Debug) log(s"materialized value source: ${struct.hash(c)}")
            val ms = c.copyOf match {
              case g: GraphStageModule ⇒ g.stage.asInstanceOf[MaterializedValueSource[Any]]
            }
            if (Debug) require(find(ms.computation, m.materializedValueComputation), s"mismatch:\n  ${ms.computation}\n  ${m.materializedValueComputation}")
            val replacement = CopiedModule(c.shape, c.attributes, new MaterializedValueSource[Any](newMat, ms.out).module)
            struct.replace(c, replacement, localGroup)
          }
          // the result for each level is the materialized value computation
          newMat
      }
    }
  }

  private def find(node: MaterializedValueNode, inTree: MaterializedValueNode): Boolean =
    if (node == inTree) true
    else
      inTree match {
        case Atomic(_)               ⇒ false
        case Ignore                  ⇒ false
        case Transform(_, dep)       ⇒ find(node, dep)
        case Combine(_, left, right) ⇒ find(node, left) || find(node, right)
      }

  private def rewriteMat(subMat: Predef.Map[Module, MaterializedValueNode],
                         mat: MaterializedValueNode): MaterializedValueNode =
    mat match {
      case Atomic(sub)             ⇒ subMat(sub)
      case Combine(f, left, right) ⇒ Combine(f, rewriteMat(subMat, left), rewriteMat(subMat, right))
      case Transform(f, dep)       ⇒ Transform(f, rewriteMat(subMat, dep))
      case Ignore                  ⇒ Ignore
    }

  private implicit class NonNull[T](val x: T) extends AnyVal {
    def nonNull(msg: String): T =
      if (x != null) x
      else throw new IllegalArgumentException("null encountered: " + msg)
  }

  /**
   * INTERNAL API
   *
   * Collect structural information about a module tree while descending into
   * it and performing normalization.
   */
  final class BuildStructuralInfo {
    def toInfo: StructuralInfo =
      StructuralInfo(
        immutable.Map.empty ++ upstreams.asScala,
        immutable.Map.empty ++ downstreams.asScala,
        immutable.Map.empty ++ inOwners.asScala,
        immutable.Map.empty ++ outOwners.asScala)

    /**
     * the set of all contained modules
     */
    val modules: ju.Set[Module] = new ju.HashSet

    /**
     * the list of all groups of modules that are within each async boundary
     */
    val groups: ju.Deque[ju.Set[Module]] = new ju.LinkedList

    /**
     * Fusable groups may contain modules with differing dispatchers, in which
     * case the group needs to be broken up.
     */
    def breakUpGroupsByDispatcher(): Unit = {
      val newGroups: ju.List[ju.Set[Module]] = new ju.LinkedList
      val it = groups.iterator()
      while (it.hasNext) {
        val group = it.next()
        if (group.size > 1) {
          val subgroups = group.asScala.groupBy(dispatcher)
          if (subgroups.size > 1) {
            group.clear()
            subgroups.valuesIterator.foreach(g ⇒ newGroups.add(g.asJava))
          }
        }
      }
      groups.addAll(newGroups)
    }

    /**
     * a mapping from OutPort to its containing group, needed when determining
     * whether an upstream connection is internal or not
     */
    val outGroup: ju.Map[OutPort, ju.Set[Module]] = new ju.HashMap

    def replace(oldMod: Module, newMod: Module, localGroup: ju.Set[Module]): Unit = {
      modules.remove(oldMod)
      modules.add(newMod)
      localGroup.remove(oldMod)
      localGroup.add(newMod)
    }

    /**
     * A stack of mappings for a given non-copied InPort.
     */
    val newIns: ju.Map[InPort, List[InPort]] = new ju.HashMap
    /**
     * A stack of mappings for a given non-copied OutPort.
     */
    val newOuts: ju.Map[OutPort, List[OutPort]] = new ju.HashMap

    private def addMapping[T](orig: T, mapd: T, map: ju.Map[T, List[T]]): Unit = {
      if (map.containsKey(orig)) {
        map.put(orig, mapd :: map.get(orig))
      } else map.put(orig, mapd :: Nil)
    }

    private def removeMapping[T](orig: T, map: ju.Map[T, List[T]]): T =
      map.remove(orig) match {
        case null     ⇒ null.asInstanceOf[T]
        case x :: Nil ⇒ x
        case x :: xs ⇒
          map.put(orig, xs)
          x
      }

    /**
     * A stack of materialized value sources, grouped by materialized computation context.
     */
    private var matSrc: List[List[CopiedModule]] = Nil

    def enterMatCtx(): Unit = matSrc ::= Nil
    def exitMatCtx(): List[CopiedModule] =
      matSrc match {
        case x :: xs ⇒
          matSrc = xs
          x
        case Nil ⇒ throw new IllegalArgumentException("exitMatCtx with empty stack")
      }
    def pushMatSrc(m: CopiedModule): Unit =
      matSrc match {
        case x :: xs ⇒ matSrc = (m :: x) :: xs
        case Nil     ⇒ throw new IllegalArgumentException("pushMatSrc without context")
      }

    /**
     * The downstreams relationships of the original module rewritten in terms of
     * the copied ports.
     */
    val downstreams: ju.Map[OutPort, InPort] = new ju.HashMap
    /**
     * The upstreams relationships of the original module rewritten in terms of
     * the copied ports.
     */
    val upstreams: ju.Map[InPort, OutPort] = new ju.HashMap

    /**
     * The owner mapping for the copied InPorts.
     */
    val inOwners: ju.Map[InPort, Module] = new ju.HashMap
    /**
     * The owner mapping for the copied OutPorts.
     */
    val outOwners: ju.Map[OutPort, Module] = new ju.HashMap

    def dump(): Unit = {
      println("StructuralInfo:")
      println("  newIns:")
      newIns.asScala.foreach { case (k, v) ⇒ println(s"    $k (${hash(k)}) -> ${v.map(hash).mkString(",")}") }
      println("  newOuts:")
      newOuts.asScala.foreach { case (k, v) ⇒ println(s"    $k (${hash(k)}) -> ${v.map(hash).mkString(",")}") }
    }

    def hash(obj: AnyRef) = f"${System.identityHashCode(obj)}%08x"
    def printShape(s: Shape) = s"${s.getClass.getSimpleName}(ins=${s.inlets.map(hash).mkString(",")} outs=${s.outlets.map(hash).mkString(",")})"

    /**
     * Create and return a new grouping (i.e. an AsyncBoundary-delimited context)
     */
    def newGroup(indent: String): ju.Set[Module] = {
      val group = new ju.HashSet[Module]
      if (Debug) println(indent + s"creating new group ${hash(group)}")
      groups.add(group)
      group
    }

    /**
     * Add a module to the given group, performing normalization (i.e. giving it a unique port identity).
     */
    def addModule(m: Module, group: ju.Set[Module], inheritedAttributes: Attributes, indent: String): Atomic = {
      val copy = CopiedModule(m.shape.deepCopy(), inheritedAttributes, realModule(m))
      if (Debug) println(indent + s"adding copy ${hash(copy)} ${printShape(copy.shape)} of ${printShape(m.shape)}")
      group.add(copy)
      modules.add(copy)
      copy.shape.outlets.foreach(o ⇒ outGroup.put(o, group))
      val orig1 = m.shape.inlets.iterator
      val mapd1 = copy.shape.inlets.iterator
      while (orig1.hasNext) {
        val orig = orig1.next()
        val mapd = mapd1.next()
        addMapping(orig, mapd, newIns)
        inOwners.put(mapd, copy)
      }
      val orig2 = m.shape.outlets.iterator
      val mapd2 = copy.shape.outlets.iterator
      while (orig2.hasNext) {
        val orig = orig2.next()
        val mapd = mapd2.next()
        addMapping(orig, mapd, newOuts)
        outOwners.put(mapd, copy)
      }
      copy.copyOf match {
        case GraphStageModule(_, _, _: MaterializedValueSource[_]) ⇒ pushMatSrc(copy)
        case _ ⇒
      }
      Atomic(copy)
    }

    /**
     * Record a wiring between two copied ports, using (and reducing) the port
     * mappings.
     */
    def wire(out: OutPort, in: InPort, indent: String): Unit = {
      if (Debug) println(indent + s"wiring $out (${hash(out)}) -> $in (${hash(in)})")
      val newOut = removeMapping(out, newOuts) nonNull out.toString
      val newIn = removeMapping(in, newIns) nonNull in.toString
      downstreams.put(newOut, newIn)
      upstreams.put(newIn, newOut)
    }

    /**
     * Replace all mappings for a given shape with its new (copied) form.
     */
    def rewire(oldShape: Shape, newShape: Shape, indent: String): Unit = {
      if (Debug) println(indent + s"rewiring ${printShape(oldShape)} -> ${printShape(newShape)}")
      oldShape.inlets.iterator.zip(newShape.inlets.iterator).foreach {
        case (oldIn, newIn) ⇒ addMapping(newIn, removeMapping(oldIn, newIns) nonNull oldIn.toString, newIns)
      }
      oldShape.outlets.iterator.zip(newShape.outlets.iterator).foreach {
        case (oldOut, newOut) ⇒ addMapping(newOut, removeMapping(oldOut, newOuts) nonNull oldOut.toString, newOuts)
      }
    }

    /**
     * Transform original into copied Inlets.
     */
    def newInlets(old: immutable.Seq[Inlet[_]]): immutable.Seq[Inlet[_]] =
      old.map(i ⇒ newIns.get(i).head.inlet)

    /**
     * Transform original into copied Inlets.
     */
    def newOutlets(old: immutable.Seq[Outlet[_]]): immutable.Seq[Outlet[_]] =
      old.map(o ⇒ newOuts.get(o).head.outlet)
  }

  private def isAsync(m: Module): Boolean = m match {
    case CopiedModule(_, inherited, orig) ⇒
      val attr = inherited and orig.attributes
      attr.contains(AsyncBoundary)
  }

  /**
   * Figure out the dispatcher setting of a module.
   */
  private def dispatcher(m: Module): Option[ActorAttributes.Dispatcher] = m match {
    case CopiedModule(_, inherited, orig) ⇒
      val attr = inherited and orig.attributes
      attr.get[ActorAttributes.Dispatcher]
    case x ⇒ x.attributes.get[ActorAttributes.Dispatcher]
  }

  private def realModule(m: Module): Module = m match {
    case CopiedModule(_, _, of) ⇒ realModule(of)
    case other                  ⇒ other
  }
}
