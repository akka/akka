/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import scala.util.control.NoStackTrace

/**
 * A predefined exception that can be used in tests. It doesn't include a stack trace.
 */
class TestException(message: String) extends RuntimeException(message) with NoStackTrace

