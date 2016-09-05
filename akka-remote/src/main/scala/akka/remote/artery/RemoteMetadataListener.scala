/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import akka.util.ByteString

trait RemoteMetadataListener {
  def accept(id: Int, metadata: ByteString): Unit
}
