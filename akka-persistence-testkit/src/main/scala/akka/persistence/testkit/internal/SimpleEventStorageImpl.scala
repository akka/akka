/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.EventStorage.Metadata

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SimpleEventStorageImpl extends EventStorage {

  override type InternalRepr = (PersistentRepr, Metadata)

  override def toInternal(repr: (PersistentRepr, Metadata)): (PersistentRepr, Metadata) = repr

  override def toRepr(internal: (PersistentRepr, Metadata)): (PersistentRepr, Metadata) = internal

}
