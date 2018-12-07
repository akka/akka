/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

/**
 * INTERNAL API
 *
 * based on https://github.com/scala/scala-collection-compat/blob/master/compat/src/main/scala-2.13/scala/collection/compat/package.scala
 */
package object ccompat {
  type Factory[-A, +C] = scala.collection.Factory[A, C]
  val Factory = scala.collection.Factory
}
