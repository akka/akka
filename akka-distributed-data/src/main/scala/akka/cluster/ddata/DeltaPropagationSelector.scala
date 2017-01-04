/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import scala.collection.immutable.TreeMap
import akka.cluster.ddata.Replicator.Internal.DeltaPropagation
import akka.actor.Address
import akka.cluster.ddata.Replicator.Internal.DataEnvelope

/**
 * INTERNAL API: Used by the Replicator actor.
 * Extracted to separate trait to make it easy to test.
 */
private[akka] trait DeltaPropagationSelector {

  private var _propagationCount = 0L
  def propagationCount: Long = _propagationCount
  private var deltaCounter = Map.empty[String, Long]
  private var deltaEntries = Map.empty[String, TreeMap[Long, ReplicatedData]]
  private var deltaSentToNode = Map.empty[String, Map[Address, Long]]
  private var deltaNodeRoundRobinCounter = 0L

  def divisor: Int

  def allNodes: Vector[Address]

  def createDeltaPropagation(deltas: Map[String, ReplicatedData]): DeltaPropagation

  def update(key: String, delta: ReplicatedData): Unit = {
    val c = deltaCounter.get(key) match {
      case Some(c) ⇒ c
      case None ⇒
        deltaCounter = deltaCounter.updated(key, 1L)
        1L
    }
    val deltaEntriesForKey = deltaEntries.getOrElse(key, TreeMap.empty[Long, ReplicatedData])
    val updatedEntriesForKey =
      deltaEntriesForKey.get(c) match {
        case Some(existingDelta) ⇒
          deltaEntriesForKey.updated(c, existingDelta.merge(delta.asInstanceOf[existingDelta.T]))
        case None ⇒
          deltaEntriesForKey.updated(c, delta)
      }
    deltaEntries = deltaEntries.updated(key, updatedEntriesForKey)
  }

  def delete(key: String): Unit = {
    deltaEntries -= key
    deltaCounter -= key
    deltaSentToNode -= key
  }

  def nodesSliceSize(allNodesSize: Int): Int = {
    // 2 - 10 nodes
    math.min(math.max((allNodesSize / divisor) + 1, 2), math.min(allNodesSize, 10))
  }

  def collectPropagations(): Map[Address, DeltaPropagation] = {
    _propagationCount += 1
    val all = allNodes
    if (all.isEmpty)
      Map.empty
    else {
      // For each tick we pick a few nodes in round-robin fashion, 2 - 10 nodes for each tick.
      // Normally the delta is propagated to all nodes within the gossip tick, so that
      // full state gossip is not needed.
      val sliceSize = nodesSliceSize(all.size)
      val slice = {
        if (all.size <= sliceSize)
          all
        else {
          val i = (deltaNodeRoundRobinCounter % all.size).toInt
          val first = all.slice(i, i + sliceSize)
          if (first.size == sliceSize) first
          else first ++ all.take(sliceSize - first.size)
        }
      }
      deltaNodeRoundRobinCounter += sliceSize

      var result = Map.empty[Address, DeltaPropagation]

      slice.foreach { node ⇒
        // collect the deltas that have not already been sent to the node and merge
        // them into a delta group
        var deltas = Map.empty[String, ReplicatedData]
        deltaEntries.foreach {
          case (key, entries) ⇒
            val deltaSentToNodeForKey = deltaSentToNode.getOrElse(key, TreeMap.empty[Address, Long])
            val j = deltaSentToNodeForKey.getOrElse(node, 0L)
            val deltaEntriesAfterJ = deltaEntriesAfter(entries, j)
            if (deltaEntriesAfterJ.nonEmpty) {
              val deltaGroup = deltaEntriesAfterJ.valuesIterator.reduceLeft {
                (d1, d2) ⇒ d1.merge(d2.asInstanceOf[d1.T])
              }
              deltas = deltas.updated(key, deltaGroup)
              deltaSentToNode = deltaSentToNode.updated(key, deltaSentToNodeForKey.updated(node, deltaEntriesAfterJ.lastKey))
            }
        }

        if (deltas.nonEmpty) {
          // Important to include the pruning state in the deltas. For example if the delta is based
          // on an entry that has been pruned but that has not yet been performed on the target node.
          val deltaPropagation = createDeltaPropagation(deltas)
          result = result.updated(node, deltaPropagation)
        }
      }

      // increase the counter
      deltaCounter = deltaCounter.map {
        case (key, value) ⇒
          if (deltaEntries.contains(key))
            key → (value + 1)
          else
            key → value
      }

      result
    }
  }

  private def deltaEntriesAfter(entries: TreeMap[Long, ReplicatedData], version: Long): TreeMap[Long, ReplicatedData] =
    entries.from(version) match {
      case ntrs if ntrs.isEmpty             ⇒ ntrs
      case ntrs if ntrs.firstKey == version ⇒ ntrs.tail // exclude first, i.e. version j that was already sent
      case ntrs                             ⇒ ntrs
    }

  def hasDeltaEntries(key: String): Boolean = {
    deltaEntries.get(key) match {
      case Some(m) ⇒ m.nonEmpty
      case None    ⇒ false
    }
  }

  private def findSmallestVersionPropagatedToAllNodes(key: String, all: Vector[Address]): Long = {
    deltaSentToNode.get(key) match {
      case None ⇒ 0L
      case Some(deltaSentToNodeForKey) ⇒
        if (deltaSentToNodeForKey.isEmpty) 0L
        else if (all.exists(node ⇒ !deltaSentToNodeForKey.contains(node))) 0L
        else deltaSentToNodeForKey.valuesIterator.min
    }
  }

  def cleanupDeltaEntries(): Unit = {
    val all = allNodes
    if (all.isEmpty)
      deltaEntries = Map.empty
    else {
      deltaEntries = deltaEntries.map {
        case (key, entries) ⇒
          val minVersion = findSmallestVersionPropagatedToAllNodes(key, all)

          val deltaEntriesAfterMin = deltaEntriesAfter(entries, minVersion)

          // TODO perhaps also remove oldest when deltaCounter are too far ahead (e.g. 10 cylces)

          key → deltaEntriesAfterMin
      }
    }
  }

  def cleanupRemovedNode(address: Address): Unit = {
    deltaSentToNode = deltaSentToNode.map {
      case (key, deltaSentToNodeForKey) ⇒
        key → (deltaSentToNodeForKey - address)
    }
  }
}
