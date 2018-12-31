/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.nio.ByteOrder

import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
package object internal {

  /**
   * INTERNAL API
   *
   * We know we always want to use network byte order when writing
   */
  @InternalApi
  private[akka] implicit val networkByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

}
