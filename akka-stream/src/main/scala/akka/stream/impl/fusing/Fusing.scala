/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.{ util ⇒ ju }
import scala.collection.immutable
import scala.collection.JavaConverters._
import akka.stream._
import akka.stream.impl.StreamLayout._
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.Attributes.AsyncBoundary
import scala.util.control.NonFatal
import akka.stream.stage.GraphStageWithMaterializedValue
import scala.annotation.tailrec
import java.util.Arrays

object Fusing {

  final val Debug = false

  def aggressive[S <: Shape, M](g: Graph[S, M]): FusedGraph[S, M] = {
    val struct = new BuildStructuralInfo
    val matValue =
      try descend(g.module, Attributes.none, struct, struct.newGroup(""), "")
      catch {
        case NonFatal(ex) ⇒
          if (Debug) struct.dump()
          throw ex
      }
    val shape = g.shape.copyFromPorts(
      struct.newInlets(g.shape.inlets),
      struct.newOutlets(g.shape.outlets)).asInstanceOf[S]
    // must come first, fuse() removes assembly-internal connections from struct
    val info = struct.toInfo
    val modules = fuse(struct)
    val module = CompositeModule(
      modules,
      shape,
      immutable.Map.empty ++ struct.downstreams.asScala,
      immutable.Map.empty ++ struct.upstreams.asScala,
      matValue,
      Attributes.none,
      info)
    validate(module)
    FusedGraph(module, shape)
  }

  case class FusedGraph[S <: Shape, M](override val module: Module,
                                       override val shape: S) extends Graph[S, M] {
    override def withAttributes(attr: Attributes) = copy(module = module.withAttributes(attr))
  }

  private def fuse(struct: BuildStructuralInfo): Set[Module] =
    struct.groups.asScala.flatMap { group ⇒
      if (group.size == 0) Nil
      else if (group.size == 1) group.iterator.next() :: Nil
      else fuseGroup(struct, group) :: Nil
    }(collection.breakOut)

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

    var pos = 0
    var it = group.iterator
    val ups = struct.upstreams
    val downs = struct.downstreams
    val outGroup = struct.outGroup
    while (it.hasNext) it.next() match {
      case copy @ CopiedModule(shape, attr, gsm: GraphStageModule) ⇒
        stages(pos) = gsm.stage
        matValIDs(pos) = copy
        attributes(pos) = attr

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
    GraphModule(new GraphInterpreter.GraphAssembly(stages, attributes, ins, inOwners, outs, outOwners), shape, Attributes.none, matValIDs)
  }

  @tailrec private def copyToArray[T](it: ju.Iterator[T], array: Array[T], idx: Int): Int =
    if (it.hasNext) {
      array(idx) = it.next()
      copyToArray(it, array, idx + 1)
    } else idx

  private def descend(m: Module,
                      inheritedAttributes: Attributes,
                      struct: BuildStructuralInfo,
                      openGroup: ju.Set[Module],
                      indent: String): MaterializedValueNode = {
    def log(msg: String): Unit = println(indent + msg)
    val async = m match {
      case _: GraphStageModule ⇒ m.attributes.contains(AsyncBoundary)
      case _ if m.isAtomic     ⇒ true
      case _                   ⇒ m.attributes.contains(AsyncBoundary)
    }
    if (Debug) log(s"entering ${m.getClass} (async=$async, name=${m.attributes.nameLifted})")
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
          struct.enterMatCtx()
          val subMat: Predef.Map[Module, MaterializedValueNode] =
            m.subModules.map(sub ⇒ sub -> descend(sub, attributes, struct, localGroup, indent + "  "))(collection.breakOut)
          val down = m.downstreams.toSet -- m.subModules.map(_.downstreams).reduce(_ ++ _)
          down.foreach {
            case (start, end) ⇒ struct.wire(start, end, indent)
          }
          val newMat = rewriteMat(subMat, m.materializedValueComputation)
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
          newMat
      }
    }
  }

  private def find(m1: MaterializedValueNode, m2: MaterializedValueNode): Boolean =
    if (m1 == m2) true
    else
      m2 match {
        case Atomic(_)               ⇒ false
        case Ignore                  ⇒ false
        case Transform(_, dep)       ⇒ find(m1, dep)
        case Combine(_, left, right) ⇒ find(m1, left) || find(m1, right)
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

  final case class StructuralInfo(upstreams: immutable.Map[InPort, OutPort], downstreams: immutable.Map[OutPort, InPort])

  final class BuildStructuralInfo {
    def toInfo: StructuralInfo =
      StructuralInfo(immutable.Map.empty ++ upstreams.asScala,
        immutable.Map.empty ++ downstreams.asScala)

    val modules: ju.Set[Module] = new ju.HashSet
    val groups: ju.Deque[ju.Set[Module]] = new ju.LinkedList

    val outGroup: ju.Map[OutPort, ju.Set[Module]] = new ju.HashMap

    def replace(oldMod: Module, newMod: Module, localGroup: ju.Set[Module]): Unit = {
      modules.remove(oldMod)
      modules.add(newMod)
      localGroup.remove(oldMod)
      localGroup.add(newMod)
    }

    val newIns: ju.Map[InPort, List[InPort]] = new ju.HashMap
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

    val downstreams: ju.Map[OutPort, InPort] = new ju.HashMap
    val upstreams: ju.Map[InPort, OutPort] = new ju.HashMap

    def dump(): Unit = {
      println("StructuralInfo:")
      println("  newIns:")
      newIns.asScala.foreach { case (k, v) ⇒ println(s"    $k (${hash(k)}) -> ${v.map(hash).mkString(",")}") }
      println("  newOuts:")
      newOuts.asScala.foreach { case (k, v) ⇒ println(s"    $k (${hash(k)}) -> ${v.map(hash).mkString(",")}") }
    }

    def hash(obj: AnyRef) = f"${System.identityHashCode(obj)}%08x"
    def printShape(s: Shape) = s"${s.getClass.getSimpleName}(ins=${s.inlets.map(hash).mkString(",")} outs=${s.outlets.map(hash).mkString(",")})"

    def newGroup(indent: String): ju.Set[Module] = {
      val group = new ju.HashSet[Module]
      if (Debug) println(indent + s"creating new group ${hash(group)}")
      groups.add(group)
      group
    }

    def addModule(m: Module, group: ju.Set[Module], inheritedAttributes: Attributes, indent: String): Atomic = {
      val copy = CopiedModule(m.shape.deepCopy(), inheritedAttributes, realModule(m))
      if (Debug) println(indent + s"adding copy ${hash(copy)} ${printShape(copy.shape)} of ${printShape(m.shape)}")
      group.add(copy)
      modules.add(copy)
      copy.shape.outlets.foreach(o ⇒ outGroup.put(o, group))
      m.shape.inlets.iterator.zip(copy.shape.inlets.iterator).foreach { p ⇒ addMapping(p._1, p._2, newIns) }
      m.shape.outlets.iterator.zip(copy.shape.outlets.iterator).foreach { p ⇒ addMapping(p._1, p._2, newOuts) }
      copy.copyOf match {
        case GraphStageModule(_, _, _: MaterializedValueSource[_]) ⇒ pushMatSrc(copy)
        case _ ⇒
      }
      Atomic(copy)
    }

    def wire(out: OutPort, in: InPort, indent: String): Unit = {
      if (Debug) println(indent + s"wiring $out (${hash(out)}) -> $in (${hash(in)})")
      val newOut = removeMapping(out, newOuts) nonNull out.toString
      val newIn = removeMapping(in, newIns) nonNull in.toString
      downstreams.put(newOut, newIn)
      upstreams.put(newIn, newOut)
    }

    def rewire(oldShape: Shape, newShape: Shape, indent: String): Unit = {
      if (Debug) println(indent + s"rewiring ${printShape(oldShape)} -> ${printShape(newShape)}")
      oldShape.inlets.iterator.zip(newShape.inlets.iterator).foreach {
        case (oldIn, newIn) ⇒ addMapping(newIn, removeMapping(oldIn, newIns) nonNull oldIn.toString, newIns)
      }
      oldShape.outlets.iterator.zip(newShape.outlets.iterator).foreach {
        case (oldOut, newOut) ⇒ addMapping(newOut, removeMapping(oldOut, newOuts) nonNull oldOut.toString, newOuts)
      }
    }

    def newInlets(old: immutable.Seq[Inlet[_]]): immutable.Seq[Inlet[_]] =
      old.map(i ⇒ newIns.get(i).head.inlet)

    def newOutlets(old: immutable.Seq[Outlet[_]]): immutable.Seq[Outlet[_]] =
      old.map(o ⇒ newOuts.get(o).head.outlet)
  }

  private def realModule(m: Module): Module = m match {
    case CopiedModule(_, _, of) ⇒ realModule(of)
    case other                  ⇒ other
  }
}
