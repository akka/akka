/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.immutable

/**
 * INTERNAL API
 */
package object imm {
  implicit class SortedSetOps[A](val real: immutable.SortedSet[A]) extends AnyVal {
    def unsorted: immutable.Set[A] = real
  }
}
