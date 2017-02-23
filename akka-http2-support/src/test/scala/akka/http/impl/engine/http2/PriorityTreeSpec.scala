/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.matchers.{ MatchResult, Matcher }

class PriorityTreeSpec extends WordSpec with Matchers {
  "PriorityTree" should {
    "contain only the root node if empty" in {
      PriorityTree() should printLike(
        """0 [weight: 16]"""
      )
    }

    "insert a single non-exclusive node under the root node" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 50, exclusive = false) should printLike(
          """0 [weight: 16]
            |  +-1 [weight: 50]
            |""".stripMargin
        )
    }
    "insert an exclusive node under the empty root node" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 50, exclusive = true) should printLike(
          """0 [weight: 16]
            |  +-1 [weight: 50]
            |""".stripMargin
        )
    }
    "insert a node with an dependency" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 50, exclusive = false)
        .insertOrUpdate(3, 1, 42, exclusive = false) should printLike(
          """0 [weight: 16]
            |  +-1 [weight: 50]
            |    +-3 [weight: 42]
            |""".stripMargin
        )
    }
    "insert an exclusive node under the root node with one existing sibling" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 30, exclusive = false)
        .insertOrUpdate(3, 0, 50, exclusive = true) should printLike(
          """0 [weight: 16]
            |  +-3 [weight: 50]
            |    +-1 [weight: 30]
            |""".stripMargin
        )
    }

    "update the priority of a node" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 50, exclusive = false)
        .insertOrUpdate(3, 0, 25, exclusive = false)
        .insertOrUpdate(1, 0, 133, exclusive = false) should printLike(
          """0 [weight: 16]
            |  +-1 [weight: 133]
            |  +-3 [weight: 25]
            |""".stripMargin
        )
    }
    "update a node setting exclusivity for a node" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 50, exclusive = false)
        .insertOrUpdate(3, 0, 25, exclusive = false)
        .insertOrUpdate(1, 0, 133, exclusive = true) should printLike(
          """0 [weight: 16]
          |  +-1 [weight: 133]
          |    +-3 [weight: 25]
          |""".stripMargin
        )
    }
    "update the dependency of a non-exclusive node" in {
      PriorityTree()
        .insertOrUpdate(1, 0, 50, exclusive = false)
        .insertOrUpdate(3, 0, 25, exclusive = false)
        .insertOrUpdate(1, 3, 133, exclusive = false) should printLike(
          """0 [weight: 16]
          |  +-3 [weight: 25]
          |    +-1 [weight: 133]
          |""".stripMargin
        )
    }
    "update the dependency of a non-exclusive node that had siblings" in {
      val origin =
        PriorityTree()
          .insertOrUpdate(1, 0, 50, exclusive = false)
          .insertOrUpdate(3, 0, 25, exclusive = false)
          .insertOrUpdate(5, 0, 100, exclusive = false)

      origin should printLike(
        """0 [weight: 16]
          |  +-1 [weight: 50]
          |  +-3 [weight: 25]
          |  +-5 [weight: 100]
        """.stripMargin
      )

      origin
        .insertOrUpdate(1, 3, 133, exclusive = false) should printLike(
          """0 [weight: 16]
            |  +-3 [weight: 25]
            |  | +-1 [weight: 133]
            |  |
            |  +-5 [weight: 100]
            |""".stripMargin
        )
    }
    "update the dependency of an exclusive node" in {
      val origin =
        PriorityTree()
          .insertOrUpdate(1, 0, 50, exclusive = false)
          .insertOrUpdate(3, 1, 25, exclusive = false)

      origin should printLike(
        """0 [weight: 16]
          |  +-1 [weight: 50]
          |    +-3 [weight: 25]
        """.stripMargin
      )

      origin // now moving up to be exclusive children of 0
        .insertOrUpdate(3, 0, 133, exclusive = true) should printLike(
          """0 [weight: 16]
            |  +-3 [weight: 133]
            |    +-1 [weight: 50]
            |""".stripMargin
        )
    }
    "update the dependency of a node with children non-exclusively" in {
      val origin =
        PriorityTree()
          .insertOrUpdate(1, 0, 50, exclusive = false)
          .insertOrUpdate(3, 1, 25, exclusive = false)
          .insertOrUpdate(5, 0, 100, exclusive = false)

      origin should printLike(
        """0 [weight: 16]
          |  +-1 [weight: 50]
          |  | +-3 [weight: 25]
          |  |
          |  +-5 [weight: 100]
        """.stripMargin
      )

      origin
        .insertOrUpdate(1, 5, 23, exclusive = false) should printLike(
          """0 [weight: 16]
            |  +-5 [weight: 100]
            |    +-1 [weight: 23]
            |      +-3 [weight: 25]
            |""".stripMargin
        )
    }
    "update the dependency of a node with children exclusively" in {
      val origin =
        PriorityTree()
          .insertOrUpdate(1, 0, 50, exclusive = false)
          .insertOrUpdate(3, 1, 25, exclusive = false)
          .insertOrUpdate(5, 0, 100, exclusive = false)
          .insertOrUpdate(7, 5, 42, exclusive = false)

      origin should printLike(
        """0 [weight: 16]
          |  +-1 [weight: 50]
          |  | +-3 [weight: 25]
          |  |
          |  +-5 [weight: 100]
          |    +-7 [weight: 42]
        """.stripMargin
      )

      origin
        .insertOrUpdate(1, 5, 23, exclusive = true) should printLike(
          """0 [weight: 16]
            |  +-5 [weight: 100]
            |    +-1 [weight: 23]
            |      +-3 [weight: 25]
            |      +-7 [weight: 42]
            |""".stripMargin
        )
    }
    "update the dependency to a former children" in {
      pending // a bit tricky
      val origin =
        PriorityTree()
          .insertOrUpdate(1, 0, 50, exclusive = false)
          .insertOrUpdate(3, 1, 25, exclusive = false)
          .insertOrUpdate(5, 0, 100, exclusive = false)
          .insertOrUpdate(7, 1, 33, exclusive = false)

      origin should printLike(
        """0 [weight: 16]
          |  +-1 [weight: 50]
          |  | +-3 [weight: 25]
          |  | +-7 [weight: 33]
          |  |
          |  +-5 [weight: 100]
        """.stripMargin
      )

      origin
        .insertOrUpdate(1, 3, 23, exclusive = false) should printLike(
          """0 [weight: 16]
            |  +-3 [weight: 133]
            |  | +-1 [weight: 50]
            |  |   +-7 [weight: 33]
            |  |
            |  +-5 [weight: 100]
            |""".stripMargin
        )
    }

    "spec example 5.3.1" in pending
    "spec example 5.3.3" in pending

    "prune nodes" in pending

    "fail when updating the root node" in pending
    "fail when introducing a dependency on itself" in pending
  }

  def printLike(resultingTree: String): Matcher[PriorityTree] =
    Matcher { tree ⇒
      val WithPotentialEndOfLineWhiteSpace = """(.*?)\s*""".r
      // The tree printer sometimes inserts blanks.
      def trimLines(str: String): String =
        str
          .split('\n')
          .map {
            case WithPotentialEndOfLineWhiteSpace(line) ⇒ line
          }
          .mkString("\n")
          .trim
      val candidate = trimLines(tree.print)
      val expected = trimLines(resultingTree)

      MatchResult(
        candidate === expected,
        s"Does not render as\n$expected\nbut as\n$candidate",
        "XXX"
      )
    }
}
