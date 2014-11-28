/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] final case class Vertex[E, V](label: V) {
  private var inEdgeSet = Set.empty[Edge[E, V]]
  private var outEdgeSet = Set.empty[Edge[E, V]]

  def addOutEdge(e: Edge[E, V]): Unit = outEdgeSet += e
  def addInEdge(e: Edge[E, V]): Unit = inEdgeSet += e

  def removeOutEdge(e: Edge[E, V]): Unit = outEdgeSet -= e
  def removeInEdge(e: Edge[E, V]): Unit = inEdgeSet -= e

  def inDegree: Int = inEdgeSet.size
  def outDegree: Int = outEdgeSet.size

  def isolated: Boolean = inDegree == 0 && outDegree == 0

  // FIXME #16381 this is at the wrong level
  def isSink: Boolean = outEdgeSet.isEmpty

  def successors: Set[Vertex[E, V]] = outEdgeSet.map(_.to)
  def predecessors: Set[Vertex[E, V]] = inEdgeSet.map(_.from)

  def neighbors: Set[Vertex[E, V]] = successors ++ predecessors

  def incoming: Set[Edge[E, V]] = inEdgeSet
  def outgoing: Set[Edge[E, V]] = outEdgeSet

  override def equals(obj: Any): Boolean = obj match {
    case v: Vertex[_, _] ⇒ label.equals(v.label)
    case _               ⇒ false
  }

  override def hashCode(): Int = label.hashCode()
}

/**
 * INTERNAL API
 */
private[akka] final case class Edge[E, V](label: E, from: Vertex[E, V], to: Vertex[E, V]) {

  override def equals(obj: Any): Boolean = obj match {
    case e: Edge[_, _] ⇒ label.equals(e.label)
    case _             ⇒ false
  }

  override def hashCode(): Int = label.hashCode()
}

/**
 * INTERNAL API
 */
private[akka] class DirectedGraphBuilder[E, V] {
  private var vertexMap = Map.empty[V, Vertex[E, V]]
  private var edgeMap = Map.empty[E, Edge[E, V]]

  def edges: immutable.Seq[Edge[E, V]] = edgeMap.values.toVector

  def nodes: immutable.Seq[Vertex[E, V]] = vertexMap.values.toVector

  def nonEmpty: Boolean = vertexMap.nonEmpty

  def addVertex(v: V): Vertex[E, V] = vertexMap.get(v) match {
    case None ⇒
      val vx = Vertex[E, V](v)
      vertexMap += v -> vx
      vx

    case Some(vx) ⇒ vx
  }

  def addEdge(from: V, to: V, label: E): Unit = {
    val vfrom = addVertex(from)
    val vto = addVertex(to)

    removeEdge(label) // Need to remap existing labels
    val edge = Edge[E, V](label, vfrom, vto)
    edgeMap += label -> edge

    vfrom.addOutEdge(edge)
    vto.addInEdge(edge)

  }

  def find(v: V): Option[Vertex[E, V]] = vertexMap.get(v)

  def get(v: V): Vertex[E, V] = vertexMap(v)

  def contains(v: V): Boolean = vertexMap.contains(v)

  def containsEdge(e: E): Boolean = edgeMap.contains(e)

  def exists(p: Vertex[E, V] ⇒ Boolean) = vertexMap.values.exists(p)

  def removeEdge(label: E): Unit = edgeMap.get(label) match {
    case Some(e) ⇒
      edgeMap -= label
      e.from.removeOutEdge(e)
      e.to.removeInEdge(e)
    case None ⇒
  }

  def remove(v: V): Unit = vertexMap.get(v) match {
    case Some(vx) ⇒
      vertexMap -= v

      vx.incoming foreach { edge ⇒ removeEdge(edge.label) }
      vx.outgoing foreach { edge ⇒ removeEdge(edge.label) }

    case None ⇒
  }

  /**
   * Performs a deep copy of the builder. Since the builder is mutable it is not safe to share instances of it
   * without making a defensive copy first.
   */
  def copy(): DirectedGraphBuilder[E, V] = {
    val result = new DirectedGraphBuilder[E, V]()

    edgeMap.foreach {
      case (label, e) ⇒
        result.addEdge(e.from.label, e.to.label, e.label)
    }

    vertexMap.filter(_._2.isolated) foreach {
      case (_, n) ⇒
        result.addVertex(n.label)
    }

    result
  }

  /**
   * Returns true if for every vertex pair there is an undirected path connecting them
   */
  def isWeaklyConnected: Boolean = {
    if (vertexMap.isEmpty) true
    else {
      var unvisited = vertexMap.values.toSet
      var toVisit = Set(unvisited.head)

      while (toVisit.nonEmpty) {
        val v = toVisit.head
        unvisited -= v
        toVisit -= v
        toVisit ++= v.neighbors.iterator.filter(unvisited.contains) // visit all unvisited neighbors of v (neighbors are undirected)
      }

      unvisited.isEmpty // if we ended up with unvisited nodes starting from one node we are unconnected
    }
  }

  /**
   * Finds a directed cycle in the graph
   */
  def findCycle: immutable.Seq[Vertex[E, V]] = {
    if (vertexMap.size < 2 || edgeMap.size < 2) Nil
    else {
      // Vertices we have not visited at all yet
      var unvisited = vertexMap.values.toSet

      // Attempts to find a cycle in a connected component
      def findCycleInComponent(
        componentEntryVertex: Vertex[E, V],
        toVisit: Vertex[E, V],
        cycleCandidate: List[Vertex[E, V]]): List[Vertex[E, V]] = {

        if (!unvisited(toVisit)) Nil
        else {
          unvisited -= toVisit

          val successors = toVisit.successors
          if (successors.contains(componentEntryVertex)) toVisit :: cycleCandidate
          else {
            val newCycleCandidate = toVisit :: cycleCandidate

            // search in all successors
            @tailrec def traverse(toTraverse: Set[Vertex[E, V]]): List[Vertex[E, V]] = {
              if (toTraverse.isEmpty) Nil
              else {
                val v = toTraverse.head
                val c = findCycleInComponent(componentEntryVertex, toVisit = v, newCycleCandidate)
                if (c.nonEmpty) c
                else traverse(toTraverse = toTraverse - v)
              }
            }

            traverse(toTraverse = successors)
          }
        }

      }

      // Traverse all weakly connected components and try to find cycles in each of them
      @tailrec def findNextCycle(): List[Vertex[E, V]] = {
        if (unvisited.size < 2) Nil
        else {
          // Pick a node to recursively start visiting its successors
          val componentEntry = unvisited.head

          if (componentEntry.inDegree < 1 || componentEntry.outDegree < 1) {
            unvisited -= componentEntry
            findNextCycle()
          } else {
            val cycleCandidate =
              findCycleInComponent(componentEntry, toVisit = componentEntry, cycleCandidate = Nil)

            if (cycleCandidate.nonEmpty) cycleCandidate
            else findNextCycle()
          }

        }

      }

      findNextCycle()
    }
  }

  def edgePredecessorBFSfoldLeft[T](start: Vertex[E, V])(zero: T)(f: (T, Edge[E, V]) ⇒ T): T = {
    var aggr: T = zero
    var unvisited = edgeMap.values.toSet
    // Queue to maintain BFS state
    var toVisit = immutable.Queue() ++ start.incoming

    while (toVisit.nonEmpty) {
      val (e, nextToVisit) = toVisit.dequeue
      toVisit = nextToVisit

      unvisited -= e
      aggr = f(aggr, e)
      val unvisitedPredecessors = e.from.incoming.filter(unvisited.contains)
      unvisited --= unvisitedPredecessors
      toVisit = toVisit ++ unvisitedPredecessors
    }

    aggr
  }

}
