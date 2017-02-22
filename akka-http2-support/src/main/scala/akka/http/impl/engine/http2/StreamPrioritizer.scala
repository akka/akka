/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import scala.collection.immutable

/** The interface for pluggable stream prioritizers */
private[http2] trait StreamPrioritizer {
  /** Update priority information for a substream */
  def updatePriority(priorityFrame: PriorityFrame): Unit

  /** Choose a substream from a set of substream ids that have data available */
  def chooseSubstream(streams: immutable.Set[Int]): Int
}

private[http2] object StreamPrioritizer {
  /** A prioritizer that ignores priority information and just sends to the first stream */
  def first(): StreamPrioritizer =
    new StreamPrioritizer {
      def updatePriority(priorityFrame: PriorityFrame): Unit = ()
      def chooseSubstream(streams: Set[Int]): Int = streams.head
    }

  def usingPriorityTree(): StreamPrioritizer =
    new StreamPrioritizer {
      private var priorityTree = PriorityTree()

      def updatePriority(info: PriorityFrame): Unit = {
        priorityTree = priorityTree.insertOrUpdate(info.streamId, info.streamDependency, info.weight, info.exclusiveFlag)
        //debug(s"Priority tree after update $info:\n${priorityTree.print}")
      }

      /** Choose a substream from a set of substream ids that have data available */
      def chooseSubstream(streams: Set[Int]): Int = {
        /**
         * Chooses one of the children, returns the chosen stream id (which must be part of `streams` or
         * -1 if no eligible stream was found in that part of the tree).
         */
        def chooseFromChildren(prioNode: PriorityNode): Int = {
          if (streams.contains(prioNode.streamId)) prioNode.streamId
          else {
            def inner(children: Seq[PriorityNode]): Int =
              if (children.nonEmpty) {
                val chosen = children.maxBy(_.weight) // choose the one with highest prio first
                val result = chooseFromChildren(chosen)
                if (result != -1) result
                else inner(children.filterNot(_ == chosen))
              } else -1

            inner(prioNode.children)
          }
        }
        val result = chooseFromChildren(priorityTree.rootNode)
        if (result == -1)
          throw new RuntimeException(s"Couldn't find one of the streams [${streams.toSeq.sorted.mkString(", ")}] in priority tree\n${priorityTree.print}")

        result
      }
    }
}