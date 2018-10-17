/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import scala.util.control.NoStackTrace

/**
 * A predefined exception that can be used in tests. It doesn't include a stack trace.
 */
class TestException(msg: String) extends RuntimeException(msg) with NoStackTrace

