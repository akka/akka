/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.coordination.lease

class LeaseException(message: String) extends RuntimeException(message)

final class LeaseTimeoutException(message: String) extends LeaseException(message)
