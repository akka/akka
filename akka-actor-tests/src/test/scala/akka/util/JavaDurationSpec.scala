/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

// FIXME workaround for https://github.com/scala/bug/issues/11512
import org.scalatest.junit.JUnitSuiteLike
import com.github.ghik.silencer.silent
@silent
class JavaDurationSpec extends JavaDuration with JUnitSuiteLike
