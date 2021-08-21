/*
 * Copyright (C) 2015-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state

import akka.annotation.ApiMayChange

/**
 * A durable state store plugin must implement a class that implements this trait.
 * It provides the concrete implementations for the Java and Scala APIs.
 *
 * A durable state store plugin plugin must provide implementations for both
 * `akka.persistence.state.scaladsl.DurableStateStore` and `akka.persistence.state.javadsl.DurableStateStore`.
 * One of the implementations can delegate to the other.
 *
 * API May Change
 */
@ApiMayChange
trait DurableStateStoreProvider {

  /**
   * The `ReadJournal` implementation for the Scala API.
   * This corresponds to the instance that is returned by [[DurableStateStoreRegistry#durableStateStoreFor]].
   */
  def scaladslDurableStateStore(): scaladsl.DurableStateStore[Any]

  /**
   * The `DurableStateStore` implementation for the Java API.
   * This corresponds to the instance that is returned by [[DurableStateStoreRegistry#getDurableStateStoreFor]].
   */
  def javadslDurableStateStore(): javadsl.DurableStateStore[AnyRef]
}
