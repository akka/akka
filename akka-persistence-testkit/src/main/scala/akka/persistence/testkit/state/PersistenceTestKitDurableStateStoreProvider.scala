/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.state

import akka.actor.ExtendedActorSystem
import akka.persistence.state.DurableStateStoreProvider
import akka.persistence.state.scaladsl.DurableStateStore
import akka.persistence.testkit.state.scaladsl.PersistenceTestKitDurableStateStore
import akka.persistence.state.javadsl.{ DurableStateStore => JDurableStateStore }
import akka.persistence.testkit.state.javadsl.{
  PersistenceTestKitDurableStateStore => JPersistenceTestKitDurableStateStore
}

class PersistenceTestKitDurableStateStoreProvider(system: ExtendedActorSystem) extends DurableStateStoreProvider {
  private def _scaladslDurableStateStore[T]() = new PersistenceTestKitDurableStateStore[T](system)
  override def scaladslDurableStateStore(): DurableStateStore[Any] = _scaladslDurableStateStore[Any]()

  override def javadslDurableStateStore(): JDurableStateStore[AnyRef] =
    new JPersistenceTestKitDurableStateStore[AnyRef](_scaladslDurableStateStore())
}
