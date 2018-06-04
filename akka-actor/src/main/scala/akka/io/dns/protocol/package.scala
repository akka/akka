/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.nio.ByteOrder

package object protocol {

  // We know we always want to use network byte order when writing
  implicit val networkByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

}
