/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

// TODO: remove this when upgrading to Scala 2.12.13
// https://github.com/scala/scala/pull/9248
package scala.annotation

class nowarn(str: String = "") extends com.github.ghik.silencer.silent(str.replaceFirst("msg=", ""))
