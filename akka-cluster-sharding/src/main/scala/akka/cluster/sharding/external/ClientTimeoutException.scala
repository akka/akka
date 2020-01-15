/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.external

final class ClientTimeoutException(reason: String) extends RuntimeException(reason)
