/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.{ util ⇒ ju }
import scala.collection.immutable
import scala.collection.JavaConverters._
import akka.stream._
import akka.stream.impl.StreamLayout._
import akka.stream.Attributes.AsyncBoundary
import scala.util.control.NonFatal

object Fusing {

  final val Debug = false

  def aggressive[S <: Shape, M](g: Graph[S, M]): FusedGraph[S, M] = {
    val struct = new StructuralInfo
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
    val module = CompositeModule(
      struct.modules.asScala.to[immutable.Set],
      shape,
      immutable.Map.empty ++ struct.downstreams.asScala,
      immutable.Map.empty ++ struct.upstreams.asScala,
      matValue,
      Attributes.none)
    validate(module)
    FusedGraph(module, shape, struct)
  }

  case class FusedGraph[S <: Shape, M](override val module: Module,
                                       override val shape: S,
                                       info: StructuralInfo) extends Graph[S, M] {
    override def withAttributes(attr: Attributes) = copy(module = module.withAttributes(attr))
  }

  private def descend(m: Module,
                      inheritedAttributes: Attributes,
                      struct: StructuralInfo,
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
      struct.addModule(m, localGroup, indent)
    } else {
      val attributes = inheritedAttributes and m.attributes
      m match {
        case CopiedModule(shape, _, copyOf) ⇒
          val ret = descend(copyOf, attributes, struct, localGroup, indent + "  ")
          struct.rewire(copyOf.shape, shape, indent)
          ret
        case _ ⇒
          val subMat: Predef.Map[Module, MaterializedValueNode] =
            m.subModules.map(sub ⇒ sub -> descend(sub, attributes, struct, localGroup, indent + "  "))(collection.breakOut)
          val down = m.downstreams.toSet -- m.subModules.map(_.downstreams).reduce(_ ++ _)
          down.foreach {
            case (start, end) ⇒ struct.wire(start, end, indent)
          }
          rewriteMat(subMat, m.materializedValueComputation)
      }
    }
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

  final class StructuralInfo {
    val modules: ju.Set[Module] = new ju.HashSet

    val groups: ju.Deque[ju.Set[Module]] = new ju.LinkedList

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

    val downstreams: ju.Map[OutPort, InPort] = new ju.HashMap
    val upstreams: ju.Map[InPort, OutPort] = new ju.HashMap

    def dump(): Unit = {
      println("StructuralInfo:")
      println("  newIns:")
      newIns.asScala.foreach { case (k, v) ⇒ println(s"    $k (${hash(k)}) -> ${v.map(hash).mkString(",")}") }
      println("  newOuts:")
      newOuts.asScala.foreach { case (k, v) ⇒ println(s"    $k (${hash(k)}) -> ${v.map(hash).mkString(",")}") }
    }

    private def hash(obj: AnyRef) = f"${System.identityHashCode(obj)}%08x"
    private def printShape(s: Shape) = s"${s.getClass.getSimpleName}(ins=${s.inlets.map(hash).mkString(",")} outs=${s.outlets.map(hash).mkString(",")})"

    def newGroup(indent: String): ju.Set[Module] = {
      val group = new ju.HashSet[Module]
      if (Debug) println(indent + s"creating new group ${hash(group)}")
      groups.add(group)
      group
    }

    def addModule(m: Module, group: ju.Set[Module], indent: String): Atomic =
      if (modules.contains(m)) {
        val copy = CopiedModule(m.shape.deepCopy(), m.attributes, realModule(m))
        if (Debug) println(indent + s"adding copy ${hash(copy)} ${printShape(copy.shape)} of ${printShape(m.shape)}")
        group.add(copy)
        modules.add(copy)
        m.inPorts.iterator.zip(copy.inPorts.iterator).foreach { p ⇒ addMapping(p._1, p._2, newIns) }
        m.outPorts.iterator.zip(copy.outPorts.iterator).foreach { p ⇒ addMapping(p._1, p._2, newOuts) }
        Atomic(copy)
      } else {
        if (Debug) println(indent + s"adding original ${hash(m)} ${printShape(m.shape)}")
        group.add(m)
        modules.add(m)
        m.inPorts.iterator.foreach { p ⇒ addMapping(p, p, newIns) }
        m.outPorts.iterator.foreach { p ⇒ addMapping(p, p, newOuts) }
        Atomic(m)
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
