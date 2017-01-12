/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import java.util.Arrays
import java.{ util ⇒ ju }

import akka.stream.Attributes.AsyncBoundary
import akka.stream.Fusing.FusedGraph
import akka.stream._
import akka.stream.impl.StreamLayout
import akka.stream.impl.StreamLayout._
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.stage.GraphStageWithMaterializedValue

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[stream] object Fusing {

  final val Debug = false

  /**
   * Fuse everything that is not forbidden via AsyncBoundary attribute.
   */
  def aggressive[S <: Shape, M](g: Graph[S, M]): FusedGraph[S, M] =
    g match {
      case fg: FusedGraph[_, _]      ⇒ fg
      case FusedGraph(module, shape) ⇒ FusedGraph(module, shape)
      case _                         ⇒ doAggressive(g)
    }

  def structuralInfo[S <: Shape, M](g: Graph[S, M], attributes: Attributes): StructuralInfoModule = {
    val struct = new BuildStructuralInfo
    /*
     * First perform normalization by descending the module tree and recording
     * information in the BuildStructuralInfo instance.
     */
    val matValue =
      try descend(g.module, Attributes.none, struct, struct.newGroup(0), 0)
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
     * Extract the full topological information from the builder
     */
    struct.toInfo(shape, matValue, attributes)
  }

  private def doAggressive[S <: Shape, M](g: Graph[S, M]): FusedGraph[S, M] = {
    val struct = new BuildStructuralInfo
    /*
     * First perform normalization by descending the module tree and recording
     * information in the BuildStructuralInfo instance.
     */
    val matValue =
      try descend(g.module, Attributes.none, struct, struct.newGroup(0), 0)
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
    val info = struct.toInfo(shape, matValue)
    /*
     * Perform the fusing of `struct.groups` into GraphModules (leaving them
     * as they are for non-fusable modules).
     */
    struct.removeInternalWires()
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
      matValue.head._2,
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
      case _ ⇒ throw new IllegalArgumentException("unexpected module structure")
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
      case _ ⇒ throw new IllegalArgumentException("unexpected module structure")
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
    val firstModule = group.iterator.next() match {
      case c: CopiedModule ⇒ c
      case _               ⇒ throw new IllegalArgumentException("unexpected module structure")
    }
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
  private def descend(
    m:                   Module,
    inheritedAttributes: Attributes,
    struct:              BuildStructuralInfo,
    openGroup:           ju.Set[Module],
    indent:              Int): List[(Module, MaterializedValueNode)] = {
    def log(msg: String): Unit = println("  " * indent + msg)
    val async = m match {
      case _: GraphStageModule ⇒ m.attributes.contains(AsyncBoundary)
      case _: GraphModule      ⇒ m.attributes.contains(AsyncBoundary)
      case _ if m.isAtomic     ⇒ true // non-GraphStage atomic or has AsyncBoundary
      case _                   ⇒ m.attributes.contains(AsyncBoundary)
    }
    if (Debug) log(s"entering ${m.getClass} (hash=${struct.hash(m)}, async=$async, name=${m.attributes.nameLifted}, dispatcher=${dispatcher(m)})")
    val localGroup =
      if (async) struct.newGroup(indent)
      else openGroup

    if (m.isAtomic) {
      m match {
        case gm: GraphModule if !async ⇒
          // need to dissolve previously fused GraphStages to allow further fusion
          if (Debug) log(s"dissolving graph module ${m.toString.replace("\n", "\n" + "  " * indent)}")
          val attributes = inheritedAttributes and m.attributes
          gm.matValIDs.flatMap(sub ⇒ descend(sub, attributes, struct, localGroup, indent + 1))(collection.breakOut)
        case gm @ GraphModule(_, oldShape, _, mvids) ⇒
          /*
           * Importing a GraphModule that has an AsyncBoundary attribute is a little more work:
           *
           *  - we need to copy all the CopiedModules that are in matValIDs
           *  - we need to rewrite the corresponding MaterializedValueNodes
           *  - we need to match up the new (copied) GraphModule shape with the individual Shape copies
           *  - we need to register the contained modules but take care to not include the internal
           *    wirings into the final result, see also `struct.removeInternalWires()`
           */
          if (Debug) log(s"graph module ${m.toString.replace("\n", "\n" + "  " * indent)}")

          // storing the old Shape in arrays for in-place updating as we clone the contained GraphStages
          val oldIns = oldShape.inlets.toArray
          val oldOuts = oldShape.outlets.toArray

          val newids = mvids.map {
            case CopiedModule(shape, attr, copyOf) ⇒
              val newShape = shape.deepCopy
              val copy = CopiedModule(newShape, attr, copyOf): Module

              // rewrite shape: first the inlets
              val oldIn = shape.inlets.iterator
              val newIn = newShape.inlets.iterator
              while (oldIn.hasNext) {
                val o = oldIn.next()
                val n = newIn.next()
                findInArray(o, oldIns) match {
                  case -1  ⇒ // nothing to do
                  case idx ⇒ oldIns(idx) = n
                }
              }
              // ... then the outlets
              val oldOut = shape.outlets.iterator
              val newOut = newShape.outlets.iterator
              while (oldOut.hasNext) {
                val o = oldOut.next()
                val n = newOut.next()
                findInArray(o, oldOuts) match {
                  case -1  ⇒ // nothing to do
                  case idx ⇒ oldOuts(idx) = n
                }
              }

              // need to add the module so that the structural (internal) wirings can be rewritten as well
              // but these modules must not be added to any of the groups
              struct.addModule(copy, new ju.HashSet, inheritedAttributes, indent, shape)
              struct.registerInternals(newShape, indent)

              copy
            case _ ⇒ throw new IllegalArgumentException("unexpected module structure")
          }
          val newgm = gm.copy(shape = oldShape.copyFromPorts(oldIns.toList, oldOuts.toList), matValIDs = newids)
          // make sure to add all the port mappings from old GraphModule Shape to new shape
          struct.addModule(newgm, localGroup, inheritedAttributes, indent, _oldShape = oldShape)
          // now compute the list of all materialized value computation updates
          var result = List.empty[(Module, MaterializedValueNode)]
          var i = 0
          while (i < mvids.length) {
            result ::= mvids(i) → Atomic(newids(i))
            i += 1
          }
          result ::= m → Atomic(newgm)
          result
        case _ ⇒
          if (Debug) log(s"atomic module $m")
          List(m → struct.addModule(m, localGroup, inheritedAttributes, indent))
      }
    } else {
      val attributes = inheritedAttributes and m.attributes
      m match {
        case CopiedModule(shape, _, copyOf) ⇒
          val ret =
            descend(copyOf, attributes, struct, localGroup, indent + 1) match {
              case xs @ (_, mat) :: _ ⇒ (m → mat) :: xs
              case _                  ⇒ throw new IllegalArgumentException("cannot happen")
            }
          struct.rewire(copyOf.shape, shape, indent)
          ret
        case _ ⇒
          // we need to keep track of all MaterializedValueSource nodes that get pushed into the current
          // computation context (i.e. that need the same value).
          struct.enterMatCtx()
          // now descend into submodules and collect their computations (plus updates to `struct`)
          val subMatBuilder = Predef.Map.newBuilder[Module, MaterializedValueNode]
          val subIterator = m.subModules.iterator
          while (subIterator.hasNext) {
            val sub = subIterator.next()
            val res = descend(sub, attributes, struct, localGroup, indent + 1)
            subMatBuilder ++= res
          }
          val subMat = subMatBuilder.result()
          if (Debug) log(subMat.map(p ⇒ s"${p._1.getClass.getName}[${struct.hash(p._1)}] -> ${p._2}").mkString("subMat\n  " + "  " * indent, "\n  " + "  " * indent, ""))
          // we need to remove all wirings that this module copied from nested modules so that we
          // don’t do wirings twice
          val oldDownstreams = m match {
            case f: FusedModule ⇒ f.info.downstreams.toSet
            case _              ⇒ m.downstreams.toSet
          }
          val down = m.subModules.foldLeft(oldDownstreams)((set, m) ⇒ set -- m.downstreams)
          down.foreach {
            case (start, end) ⇒ struct.wire(start, end, indent)
          }
          // now rewrite the materialized value computation based on the copied modules and their computation nodes
          val matNodeMapping: ju.Map[MaterializedValueNode, MaterializedValueNode] = new ju.HashMap
          val newMat = rewriteMat(subMat, m.materializedValueComputation, matNodeMapping)
          if (Debug) log(matNodeMapping.asScala.map(p ⇒ s"${p._1} -> ${p._2}").mkString("matNodeMapping\n  " + "  " * indent, "\n  " + "  " * indent, ""))
          // and finally rewire all MaterializedValueSources to their new computation nodes
          val matSrcs = struct.exitMatCtx()
          matSrcs.foreach { c ⇒
            val ms = c.copyOf.asInstanceOf[GraphStageModule].stage.asInstanceOf[MaterializedValueSource[Any]]
            val mapped = ms.computation match {
              case Atomic(sub) ⇒ subMat(sub)
              case Ignore      ⇒ Ignore
              case other       ⇒ matNodeMapping.get(other)
            }
            if (Debug) log(s"materialized value source: ${c.copyOf} -> $mapped")
            require(mapped != null, s"mismatch:\n  ${ms.computation}\n  ${m.materializedValueComputation}")
            val newSrc = new MaterializedValueSource[Any](mapped, ms.out)
            val replacement = CopiedModule(c.shape, c.attributes, newSrc.module)
            struct.replace(c, replacement, localGroup)
          }
          // the result for each level is the materialized value computation
          List(m → newMat)
      }
    }
  }

  @tailrec
  private def findInArray[T](elem: T, arr: Array[T], idx: Int = 0): Int =
    if (idx >= arr.length) -1
    else if (arr(idx) == elem) idx
    else findInArray(elem, arr, idx + 1)

  /**
   * Given a mapping from old modules to new MaterializedValueNode, rewrite the given
   * computation while also populating a mapping from old computation nodes to new ones.
   * That mapping is needed to rewrite the MaterializedValueSource stages later-on in
   * descend().
   */
  private def rewriteMat(subMat: Predef.Map[Module, MaterializedValueNode], mat: MaterializedValueNode,
                         mapping: ju.Map[MaterializedValueNode, MaterializedValueNode]): MaterializedValueNode =
    mat match {
      case Atomic(sub) ⇒
        val ret = subMat(sub)
        mapping.put(mat, ret)
        ret
      case Combine(f, left, right) ⇒
        val ret = Combine(f, rewriteMat(subMat, left, mapping), rewriteMat(subMat, right, mapping))
        mapping.put(mat, ret)
        ret
      case Transform(f, dep) ⇒
        val ret = Transform(f, rewriteMat(subMat, dep, mapping))
        mapping.put(mat, ret)
        ret
      case Ignore ⇒ Ignore
    }

  /**
   * INTERNAL API
   *
   * Collect structural information about a module tree while descending into
   * it and performing normalization.
   */
  final class BuildStructuralInfo {
    def toInfo[S <: Shape](shape: S, matValues: List[(Module, MaterializedValueNode)],
                           attributes: Attributes = Attributes.none): StructuralInfoModule =
      StructuralInfoModule(
        Set.empty ++ modules.asScala,
        shape,
        immutable.Map.empty ++ downstreams.asScala,
        immutable.Map.empty ++ upstreams.asScala,
        immutable.Map.empty ++ inOwners.asScala,
        immutable.Map.empty ++ outOwners.asScala,
        matValues,
        matValues.head._2,
        attributes)

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
        case Nil      ⇒ throw new IllegalStateException("mappings corrupted")
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

    /**
     * List of internal wirings of GraphModules that were incorporated.
     */
    val internalOuts: ju.Set[OutPort] = new ju.HashSet

    /**
     * Register the outlets of the given Shape as sources for internal
     * connections within imported (and not dissolved) GraphModules.
     * See also the comment in addModule where this is partially undone.
     */
    def registerInternals(s: Shape, indent: Int): Unit = {
      if (Debug) println("  " * indent + s"registerInternals(${s.outlets.map(hash)})")
      internalOuts.addAll(s.outlets.asJava)
    }

    /**
     * Remove wirings that belong to the fused stages contained in GraphModules
     * that were incorporated in this fusing run.
     */
    def removeInternalWires(): Unit = {
      val it = internalOuts.iterator()
      while (it.hasNext) {
        val out = it.next()
        val in = downstreams.remove(out)
        if (in != null) upstreams.remove(in)
      }
    }

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
    def newGroup(indent: Int): ju.Set[Module] = {
      val group = new ju.HashSet[Module]
      if (Debug) println("  " * indent + s"creating new group ${hash(group)}")
      groups.add(group)
      group
    }

    /**
     * Add a module to the given group, performing normalization (i.e. giving it a unique port identity).
     */
    def addModule(m: Module, group: ju.Set[Module], inheritedAttributes: Attributes, indent: Int,
                  _oldShape: Shape = null): Atomic = {
      val copy =
        if (_oldShape == null) CopiedModule(m.shape.deepCopy(), inheritedAttributes, realModule(m))
        else m
      val oldShape = if (_oldShape == null) m.shape else _oldShape
      if (Debug) println("  " * indent + s"adding copy ${hash(copy)} ${printShape(copy.shape)} of ${printShape(oldShape)}")
      group.add(copy)
      modules.add(copy)
      copy.shape.outlets.foreach(o ⇒ outGroup.put(o, group))
      val orig1 = oldShape.inlets.iterator
      val mapd1 = copy.shape.inlets.iterator
      while (orig1.hasNext) {
        val orig = orig1.next()
        val mapd = mapd1.next()
        addMapping(orig, mapd, newIns)
        inOwners.put(mapd, copy)
      }
      val orig2 = oldShape.outlets.iterator
      val mapd2 = copy.shape.outlets.iterator
      while (orig2.hasNext) {
        val orig = orig2.next()
        val mapd = mapd2.next()
        addMapping(orig, mapd, newOuts)
        outOwners.put(mapd, copy)
      }
      /*
       * In descend() we add internalOuts entries for all shapes that belong to stages that
       * are part of a GraphModule that is not dissolved. This includes the exposed Outlets,
       * which of course are external and thus need to be removed again from the internalOuts
       * set.
       */
      if (m.isInstanceOf[GraphModule]) internalOuts.removeAll(m.shape.outlets.asJava)
      copy match {
        case c @ CopiedModule(_, _, GraphStageModule(_, _, _: MaterializedValueSource[_])) ⇒ pushMatSrc(c)
        case GraphModule(_, _, _, mvids) ⇒
          var i = 0
          while (i < mvids.length) {
            mvids(i) match {
              case c @ CopiedModule(_, _, GraphStageModule(_, _, _: MaterializedValueSource[_])) ⇒ pushMatSrc(c)
              case _ ⇒
            }
            i += 1
          }
        case _ ⇒
      }
      Atomic(copy)
    }

    /**
     * Record a wiring between two copied ports, using (and reducing) the port
     * mappings.
     */
    def wire(out: OutPort, in: InPort, indent: Int): Unit = {
      if (Debug) println("  " * indent + s"wiring $out (${hash(out)}) -> $in (${hash(in)})")
      val newOut = nonNullForPort(removeMapping(out, newOuts), out)
      val newIn = nonNullForPort(removeMapping(in, newIns), in)
      downstreams.put(newOut, newIn)
      upstreams.put(newIn, newOut)
    }

    /**
     * Replace all mappings for a given shape with its new (copied) form.
     */
    def rewire(oldShape: Shape, newShape: Shape, indent: Int): Unit = {
      if (Debug) println("  " * indent + s"rewiring ${printShape(oldShape)} -> ${printShape(newShape)}")
      oldShape.inlets.iterator.zip(newShape.inlets.iterator).foreach {
        case (oldIn, newIn) ⇒ addMapping(newIn, nonNullForPort(removeMapping(oldIn, newIns), oldIn), newIns)
      }
      oldShape.outlets.iterator.zip(newShape.outlets.iterator).foreach {
        case (oldOut, newOut) ⇒ addMapping(newOut, nonNullForPort(removeMapping(oldOut, newOuts), oldOut), newOuts)
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

    // optimization - specialized null check avoiding allocation or creation of unused strings
    private def nonNullForPort[T](t: T, port: AnyRef): T = {
      if (t != null) t
      else throw new IllegalArgumentException(s"null encountered: $port (${hash(port)})")
    }

  }

  /**
   * Determine whether the given CopiedModule has an AsyncBoundary attribute.
   */
  private def isAsync(m: CopiedModule): Boolean = m match {
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

  /**
   * See through copied modules to the “real” module.
   */
  private def realModule(m: Module): Module = m match {
    case CopiedModule(_, _, of) ⇒ realModule(of)
    case other                  ⇒ other
  }
}
