/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.external

final class ClientTimeoutException(reason: String) extends RuntimeException(reason)
