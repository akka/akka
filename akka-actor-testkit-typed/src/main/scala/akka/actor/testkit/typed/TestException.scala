/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import scala.util.control.NoStackTrace

class TestException(msg: String) extends RuntimeException(msg) with NoStackTrace

