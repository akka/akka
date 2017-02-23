/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.util.AsciiTreeLayout

import scala.collection.immutable
import scala.collection.immutable.{ Seq, TreeMap, TreeSet }

/** INTERNAL API */
private[http2] trait PriorityNode {
  def streamId: Int
  def weight: Int
  def dependency: PriorityNode
  def children: immutable.Seq[PriorityNode]
}

/** INTERNAL API */
private[http2] trait PriorityTree {
  /**
   * Returns a new priority tree containing the new or existing and updated stream.
   */
  def insertOrUpdate(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean): PriorityTree

  /**
   * Returns the root node of the tree.
   */
  def rootNode: PriorityNode

  /**
   * Gives an ASCII representation of the tree.
   */
  def print: String
}

/** INTERNAL API */
private[http2] object PriorityTree {
  def apply(): PriorityTree = create(TreeMap(0 → RootNode))

  private final val DefaultWeight = 16

  private val RootNode = PriorityInfo(0, 0, DefaultWeight, TreeSet.empty)
  private def create(nodes: TreeMap[Int, PriorityInfo]): PriorityTreeImpl =
    new PriorityTreeImpl(nodes)

  private class PriorityTreeImpl(nodes: TreeMap[Int, PriorityInfo]) extends PriorityTree {
    def rootNode: PriorityNode = node(0)

    def insertOrUpdate(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean): PriorityTree =
      if (nodes.isDefinedAt(streamId)) update(streamId, streamDependency, weight, exclusive)
      else insert(streamId, streamDependency, weight, exclusive)

    private def insert(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean): PriorityTreeImpl = {
      require(!nodes.isDefinedAt(streamId), s"Cannot insert node twice: $streamId")
      require(streamId != streamDependency, s"Stream cannot depend on itself: $streamId")

      if (nodes.isDefinedAt(streamDependency)) {
        val dependencyInfo = nodes(streamDependency)
        if (!exclusive)
          insertNode(PriorityInfo(streamId, streamDependency, weight, TreeSet.empty))
            .updateNode(streamDependency)(updateChildren(_ + streamId))
        else
          insertNode(PriorityInfo(streamId, streamDependency, weight, dependencyInfo.childrenIds))
            .updateNode(streamDependency)(updateChildren(_ ⇒ TreeSet(streamId)))
      } else
        insertNode(PriorityInfo(streamDependency, 0, DefaultWeight, TreeSet.empty)) // try again after creating intermediate
          .insert(streamId, streamDependency, weight, exclusive)
    }
    private def update(streamId: Int, newStreamDependency: Int, newWeight: Int, newlyExclusive: Boolean): PriorityTree = {
      require(nodes.isDefinedAt(streamId), s"Not must exist to be updated: $streamId")
      require(streamId != newStreamDependency, s"Stream cannot depend on itself: $streamId")

      if (nodes.isDefinedAt(newStreamDependency)) {
        val oldInfo = nodes(streamId)

        if (!newlyExclusive && newStreamDependency == dependencyOf(streamId)) // no dependency changes -> simple
          updateNode(streamId)(_.copy(weight = newWeight))
        else if (!dependsTransitivelyOn(newStreamDependency, streamId)) {
          remove(streamId)
            .insert(streamId, newStreamDependency, newWeight, newlyExclusive)
            .updateNode(streamId)(updateChildren(newChildren ⇒ newChildren ++ oldInfo.childrenIds))
        } else {
          this // FIXME: actually implement moving nodes down in a dependency chain
        }
      } else
        insert(newStreamDependency, 0, DefaultWeight, exclusive = false) // try again after creating intermediate
          .update(streamId, newStreamDependency, newWeight, newlyExclusive)
    }

    private def remove(streamId: Int): PriorityTreeImpl = {
      require(nodes.isDefinedAt(streamId), s"Node must exist to be removed")
      require(streamId != 0, "Cannot remove root node")

      val info = nodes(streamId)
      val dependencyInfo = nodes(info.streamDependency)

      create(
        (nodes - streamId) +
          (info.streamDependency → dependencyInfo.copy(childrenIds = dependencyInfo.childrenIds - streamId)) ++
          info.childrenIds.map(id ⇒
            id → nodes(id).copy(streamDependency = info.streamDependency)
          )
      )
    }

    private def dependsTransitivelyOn(child: Int, parent: Int): Boolean = {
      val realParent = dependencyOf(child)

      (child != 0) && (
        realParent == parent ||
        dependsTransitivelyOn(realParent, parent))
    }

    private def dependencyOf(streamId: Int): Int = nodes(streamId).streamDependency

    // lens-like "mutators"
    private def updateNodes(updater: TreeMap[Int, PriorityInfo] ⇒ TreeMap[Int, PriorityInfo]): PriorityTreeImpl =
      create(updater(nodes))
    private def updateAll(updaters: (TreeMap[Int, PriorityInfo] ⇒ TreeMap[Int, PriorityInfo])*): PriorityTreeImpl =
      updateNodes(nodes ⇒ updaters.foldLeft(nodes)((updater, state) ⇒ state(updater)))

    private def updateNode(streamId: Int)(updater: PriorityInfo ⇒ PriorityInfo): PriorityTreeImpl =
      updateNodes { nodes ⇒
        nodes.updated(streamId, updater(nodes(streamId)))
      }
    private def updateChildren(updater: immutable.TreeSet[Int] ⇒ immutable.TreeSet[Int]): PriorityInfo ⇒ PriorityInfo = { old ⇒
      old.copy(childrenIds = updater(old.childrenIds))
    }
    private def insertNode(newNode: PriorityInfo): PriorityTreeImpl =
      updateNodes(_ + (newNode.streamId → newNode))

    def print: String = {
      def printNode(node: PriorityNode): String = s"${node.streamId} [weight: ${node.weight}]"

      AsciiTreeLayout.toAscii[PriorityNode](rootNode, _.children, printNode)
    }

    private def node(_streamId: Int): PriorityNode = new PriorityNode {
      def streamId: Int = _streamId
      def weight: Int = nodes(streamId).weight
      def dependency: PriorityNode = node(nodes(streamId).streamDependency)
      def children: immutable.Seq[PriorityNode] = nodes(streamId).childrenIds.toVector.map(node)
    }
  }

  private case class PriorityInfo(
    streamId:         Int,
    streamDependency: Int,
    weight:           Int,
    childrenIds:      immutable.TreeSet[Int]
  )
}
