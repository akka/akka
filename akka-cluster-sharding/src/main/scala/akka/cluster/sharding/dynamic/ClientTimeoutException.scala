/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

final class ClientTimeoutException(reason: String) extends RuntimeException(reason)
