/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import akka.util.ByteString

/**
 * INTERNAL API
 */
private[http] class EnhancedByteStringTraversableOnce(val byteStrings: TraversableOnce[ByteString]) extends AnyVal {
  def join: ByteString = byteStrings.foldLeft(ByteString.empty)(_ ++ _)
}
