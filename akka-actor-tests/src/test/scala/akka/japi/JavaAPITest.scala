/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.japi

// FIXME workaround for https://github.com/scala/bug/issues/11512
import org.scalatest.junit.JUnitSuiteLike

import com.github.ghik.silencer.silent
@silent
class JavaAPITest extends JavaAPITestBase with JUnitSuiteLike
