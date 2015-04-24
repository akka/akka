/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import akka.util.ByteString

/**
 * INTERNAL API
 */
private[http] class EnhancedByteStringTraversableOnce(val byteStrings: TraversableOnce[ByteString]) extends AnyVal {
  def join: ByteString = byteStrings.foldLeft(ByteString.empty)(_ ++ _)
}
