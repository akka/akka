/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.state.inmem

import akka.persistence.state.DurableStateStoreProvider
import akka.persistence.state.scaladsl.DurableStateStore
import akka.persistence.query.state.inmem.scaladsl.InmemDurableStateStore
import akka.persistence.state.javadsl.{ DurableStateStore => JDurableStateStore }
import akka.persistence.query.state.inmem.javadsl.{ InmemDurableStateStore => JInmemDurableStateStore }

class InmemDurableStateStoreProvider extends DurableStateStoreProvider {
  override def scaladslDurableStateStore(): DurableStateStore[Any] =
    new InmemDurableStateStore[Any]

  override def javadslDurableStateStore(): JDurableStateStore[AnyRef] =
    new JInmemDurableStateStore[AnyRef]
}
