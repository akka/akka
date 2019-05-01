/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

// FIXME workaround for https://github.com/scala/bug/issues/11512
import org.scalatest.junit.JUnitSuiteLike

import com.github.ghik.silencer.silent
@silent
class JavaAPISpec extends JavaAPI with JUnitSuiteLike
