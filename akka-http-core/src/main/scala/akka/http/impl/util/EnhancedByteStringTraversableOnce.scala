/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.annotation.InternalApi
import akka.util.ByteString

/**
 * INTERNAL API
 */
@InternalApi
private[http] class EnhancedByteStringTraversableOnce(val byteStrings: TraversableOnce[ByteString]) extends AnyVal {
  def join: ByteString = byteStrings.foldLeft(ByteString.empty)(_ ++ _)
}
