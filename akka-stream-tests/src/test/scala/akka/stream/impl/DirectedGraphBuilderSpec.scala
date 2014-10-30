/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.testkit.AkkaSpec

class DirectedGraphBuilderSpec extends AkkaSpec {

  "DirectedGraphBuilder" must {

    "add and remove vertices" in {
      val g = new DirectedGraphBuilder[String, Int]
      g.contains(1) should be(false)
      g.nodes.isEmpty should be(true)
      g.edges.isEmpty should be(true)
      g.find(1) should be(None)

      g.addVertex(1)

      g.contains(1) should be(true)
      g.nodes.size should be(1)
      g.edges.isEmpty should be(true)
      g.find(1) should be(Some(Vertex(1)))

      g.addVertex(1)

      g.contains(1) should be(true)
      g.nodes.size should be(1)
      g.edges.isEmpty should be(true)
      g.find(1) should be(Some(Vertex(1)))

      g.addVertex(2)

      g.contains(1) should be(true)
      g.contains(2) should be(true)
      g.nodes.size should be(2)
      g.find(1) should be(Some(Vertex(1)))
      g.find(2) should be(Some(Vertex(2)))

      g.remove(2)
      g.contains(1) should be(true)
      g.contains(2) should be(false)
      g.nodes.size should be(1)
      g.edges.isEmpty should be(true)
      g.find(1) should be(Some(Vertex(1)))
      g.find(2) should be(None)
    }

    "add and remove edges" in {
      val g = new DirectedGraphBuilder[String, Int]

      g.nodes.size should be(0)
      g.edges.size should be(0)
      g.contains(1) should be(false)
      g.contains(2) should be(false)
      g.contains(3) should be(false)
      g.containsEdge("1 -> 2") should be(false)
      g.containsEdge("2 -> 3") should be(false)

      g.addEdge(1, 2, "1 -> 2")

      g.nodes.size should be(2)
      g.edges.size should be(1)
      g.contains(1) should be(true)
      g.contains(2) should be(true)
      g.containsEdge("1 -> 2") should be(true)
      g.get(1).incoming.isEmpty should be(true)
      g.get(1).outgoing.head.label should be("1 -> 2")
      g.get(2).outgoing.isEmpty should be(true)
      g.get(2).incoming.head.label should be("1 -> 2")
      g.get(1).outgoing.head.from.label should be(1)
      g.get(1).outgoing.head.to.label should be(2)

      g.addEdge(2, 3, "2 -> 3")

      g.nodes.size should be(3)
      g.edges.size should be(2)
      g.contains(1) should be(true)
      g.contains(2) should be(true)
      g.contains(3) should be(true)
      g.containsEdge("1 -> 2") should be(true)
      g.containsEdge("2 -> 3") should be(true)
      g.get(1).incoming.isEmpty should be(true)
      g.get(1).outgoing.head.label should be("1 -> 2")
      g.get(2).outgoing.head.label should be("2 -> 3")
      g.get(2).incoming.head.label should be("1 -> 2")
      g.get(3).incoming.head.label should be("2 -> 3")
      g.get(3).outgoing.isEmpty should be(true)
      g.get(1).outgoing.head.from.label should be(1)
      g.get(1).outgoing.head.to.label should be(2)
      g.get(2).outgoing.head.from.label should be(2)
      g.get(2).outgoing.head.to.label should be(3)

      // Will reposition edge
      g.addEdge(2, 4, "2 -> 3")

      g.nodes.size should be(4)
      g.edges.size should be(2)
      g.contains(1) should be(true)
      g.contains(2) should be(true)
      g.contains(3) should be(true)
      g.contains(4) should be(true)
      g.containsEdge("1 -> 2") should be(true)
      g.containsEdge("2 -> 3") should be(true)
      g.get(1).incoming.isEmpty should be(true)
      g.get(1).outgoing.head.label should be("1 -> 2")
      g.get(2).outgoing.head.label should be("2 -> 3")
      g.get(2).incoming.head.label should be("1 -> 2")
      g.get(3).incoming.isEmpty should be(true)
      g.get(3).outgoing.isEmpty should be(true)
      g.get(4).incoming.head.label should be("2 -> 3")
      g.get(4).outgoing.isEmpty should be(true)
      g.get(1).outgoing.head.from.label should be(1)
      g.get(1).outgoing.head.to.label should be(2)
      g.get(2).outgoing.head.from.label should be(2)
      g.get(2).outgoing.head.to.label should be(4)

      // Will remove dangling edge
      g.remove(4)

      g.nodes.size should be(3)
      g.edges.size should be(1)
      g.contains(1) should be(true)
      g.contains(2) should be(true)
      g.contains(3) should be(true)
      g.contains(4) should be(false)
      g.containsEdge("1 -> 2") should be(true)
      g.containsEdge("2 -> 3") should be(false)
      g.get(1).incoming.isEmpty should be(true)
      g.get(1).outgoing.head.label should be("1 -> 2")
      g.get(2).outgoing.isEmpty should be(true)
      g.get(2).incoming.head.label should be("1 -> 2")
      g.get(3).incoming.isEmpty should be(true)
      g.get(3).outgoing.isEmpty should be(true)
      g.get(1).outgoing.head.from.label should be(1)
      g.get(1).outgoing.head.to.label should be(2)

      // Remove remaining edge
      g.removeEdge("1 -> 2")

      g.nodes.size should be(3)
      g.edges.isEmpty should be(true)
      g.contains(1) should be(true)
      g.contains(2) should be(true)
      g.contains(3) should be(true)
      g.contains(4) should be(false)
      g.containsEdge("1 -> 2") should be(false)
      g.containsEdge("2 -> 3") should be(false)
      g.get(1).incoming.isEmpty should be(true)
      g.get(1).outgoing.isEmpty should be(true)
      g.get(2).outgoing.isEmpty should be(true)
      g.get(2).incoming.isEmpty should be(true)
      g.get(3).incoming.isEmpty should be(true)
      g.get(3).outgoing.isEmpty should be(true)
    }
  }

  "work correctly with isolated nodes" in {
    val g = new DirectedGraphBuilder[String, Int]
    (1 to 99) foreach { i ⇒
      g.addVertex(i)
      g.nodes.size should be(i)
      g.find(i) should be(Some(Vertex(i)))
    }

    g.isWeaklyConnected should be(false)
    g.findCycle.isEmpty should be(true)
    g.edgePredecessorBFSfoldLeft(g.get(99))(true) { (_, _) ⇒ false } should be(true)
  }

  "work correctly with simple chains" in {
    val g = new DirectedGraphBuilder[String, Int]

    (1 to 99) foreach { i ⇒
      g.addEdge(i, i + 1, s"$i -> ${i + 1}")
      g.nodes.size should be(i + 1)
      g.edges.size should be(i)
      g.find(i) should be(Some(Vertex(i)))
      g.find(i + 1) should be(Some(Vertex(i + 1)))
      g.edges.contains(s"$i -> ${i + 1}")
    }

    g.isWeaklyConnected should be(true)
    g.findCycle.isEmpty should be(true)
    g.edgePredecessorBFSfoldLeft(g.get(100))(100) { (sum, e) ⇒ sum + e.from.label } should be(5050)

    (1 to 100) foreach (g.remove(_))
    g.nodes.isEmpty should be(true)
    g.edges.isEmpty should be(true)
  }

  "work correctly with weakly connected chains" in {
    val g = new DirectedGraphBuilder[String, Int]

    (1 to 49) foreach { i ⇒
      g.addEdge(i, i + 1, s"$i -> ${i + 1}")
      g.nodes.size should be(i + 1)
      g.edges.size should be(i)
      g.find(i) should be(Some(Vertex(i)))
      g.find(i + 1) should be(Some(Vertex(i + 1)))
      g.edges.contains(s"$i -> ${i + 1}")
    }

    (100 to 51 by -1) foreach { i ⇒
      g.addEdge(i, i - 1, s"$i -> ${i - 1}")
      g.find(i) should be(Some(Vertex(i)))
      g.find(i - 1) should be(Some(Vertex(i - 1)))
      g.edges.contains(s"$i -> ${i - 1}")
    }

    g.nodes.size should be(100)
    g.edges.size should be(99)

    g.isWeaklyConnected should be(true)
    g.findCycle.isEmpty should be(true)
    g.edgePredecessorBFSfoldLeft(g.get(50))(50) { (sum, e) ⇒ sum + e.from.label } should be(5050)

    (1 to 100) foreach (g.remove(_))
    g.nodes.isEmpty should be(true)
    g.edges.isEmpty should be(true)
  }

  "work correctly with directed cycles" in {
    val g = new DirectedGraphBuilder[String, Int]

    (1 to 99) foreach { i ⇒
      g.addEdge(i, i + 1, s"$i -> ${i + 1}")
      g.nodes.size should be(i + 1)
      g.edges.size should be(i)
      g.find(i) should be(Some(Vertex(i)))
      g.find(i + 1) should be(Some(Vertex(i + 1)))
      g.edges.contains(s"$i -> ${i + 1}")
    }
    g.addEdge(100, 1, "100 -> 1")
    g.nodes.size should be(100)
    g.edges.size should be(100)

    g.isWeaklyConnected should be(true)
    g.findCycle.toSet.size should be(100)
    g.findCycle.toSet should be((1 to 100).map(Vertex(_)).toSet)
    g.edgePredecessorBFSfoldLeft(g.get(100))(0) { (sum, e) ⇒ sum + e.from.label } should be(5050)

    (1 to 100) foreach (g.remove(_))
    g.nodes.isEmpty should be(true)
    g.edges.isEmpty should be(true)
  }

  "work correctly with undirected cycles" in {
    val g = new DirectedGraphBuilder[String, Int]

    (1 to 49) foreach { i ⇒
      g.addEdge(i, i + 1, s"$i -> ${i + 1}")
      g.nodes.size should be(i + 1)
      g.edges.size should be(i)
      g.find(i) should be(Some(Vertex(i)))
      g.find(i + 1) should be(Some(Vertex(i + 1)))
      g.edges.contains(s"$i -> ${i + 1}")
    }

    (100 to 51 by -1) foreach { i ⇒
      g.addEdge(i, i - 1, s"$i -> ${i - 1}")
      g.find(i) should be(Some(Vertex(i)))
      g.find(i - 1) should be(Some(Vertex(i - 1)))
      g.edges.contains(s"$i -> ${i - 1}")
    }

    g.addEdge(100, 1, "100 -> 1")
    g.nodes.size should be(100)
    g.edges.size should be(100)

    g.isWeaklyConnected should be(true)
    g.findCycle.isEmpty should be(true)
    g.edgePredecessorBFSfoldLeft(g.get(50))(50) { (sum, e) ⇒ sum + e.from.label } should be(5150)

    (1 to 100) foreach (g.remove(_))
    g.nodes.isEmpty should be(true)
    g.edges.isEmpty should be(true)
  }

  "work correctly with two linked cycles, both directed" in {
    val g = new DirectedGraphBuilder[String, Int]
    g.addEdge(0, 1, "0 -> 1")
    g.addEdge(1, 2, "1 -> 2")
    g.addEdge(2, 0, "2 -> 0")

    g.addEdge(1, 3, "1 -> 3")
    g.addEdge(3, 0, "3 -> 0")

    g.nodes.size should be(4)
    g.isWeaklyConnected should be(true)
    g.findCycle.nonEmpty should be(true)
    g.findCycle.size should be(3)

    g.removeEdge("1 -> 2")
    g.isWeaklyConnected should be(true)
    g.findCycle.nonEmpty should be(true)
    g.findCycle.size should be(3)
    g.findCycle.map(_.label).toSet should be(Set(0, 1, 3))

    g.removeEdge("1 -> 3")
    g.addEdge(1, 2, "1 -> 2")
    g.nodes.size should be(4)
    g.isWeaklyConnected should be(true)
    g.findCycle.nonEmpty should be(true)
    g.findCycle.size should be(3)
    g.findCycle.map(_.label).toSet should be(Set(0, 1, 2))

    g.removeEdge("1 -> 2")
    g.isWeaklyConnected should be(true)
    g.findCycle.isEmpty should be(true)
  }

  "work correctly with two linked cycles, one undirected" in {
    val g = new DirectedGraphBuilder[String, Int]
    g.addEdge(0, 1, "0 -> 1")
    g.addEdge(1, 2, "1 -> 2")
    g.addEdge(2, 0, "2 -> 0")

    g.addEdge(1, 3, "1 -> 3")
    g.addEdge(0, 3, "3 <- 0")

    g.nodes.size should be(4)
    g.isWeaklyConnected should be(true)
    g.findCycle.nonEmpty should be(true)
    g.findCycle.size should be(3)
    g.findCycle.map(_.label).toSet should be(Set(0, 1, 2))

    g.removeEdge("1 -> 2")
    g.isWeaklyConnected should be(true)
    g.findCycle.isEmpty should be(true)

    g.removeEdge("1 -> 3")
    g.isWeaklyConnected should be(true)
    g.findCycle.isEmpty should be(true)

    g.remove(0)
    g.isWeaklyConnected should be(false)
    g.findCycle.isEmpty should be(true)
  }

  "copy correctly" in {
    val g1 = new DirectedGraphBuilder[String, Int]

    (1 to 49) foreach { i ⇒
      g1.addEdge(i, i + 1, s"$i -> ${i + 1}")
    }

    (100 to 51 by -1) foreach { i ⇒
      g1.addEdge(i, i - 1, s"$i -> ${i - 1}")
    }

    g1.addEdge(0, 1, "0 -> 1")
    g1.addEdge(2, 0, "2 -> 0")

    g1.addEdge(1, 3, "1 -> 3")
    g1.addEdge(3, 0, "3 -> 0")

    g1.addVertex(200)

    val g2 = g1.copy()

    g2.nodes.size should be(102)
    g2.nodes.toSet should be(g1.nodes.toSet)
    g2.edges.toSet should be(g1.edges.toSet)

    g2.nodes foreach { v2 ⇒
      val v1 = g1.find(v2.label).get

      v1.label should be(v2.label)
      v1.incoming should be(v2.incoming)
      v1.outgoing should be(v2.outgoing)

      v1.incoming.map(_.to) should be(v2.incoming.map(_.to))
      v1.incoming.map(_.from) should be(v2.incoming.map(_.from))

      v1.outgoing.map(_.to) should be(v2.outgoing.map(_.to))
      v1.outgoing.map(_.from) should be(v2.outgoing.map(_.from))
    }
  }

}
