/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.crdt
import akka.annotation.ApiMayChange

@ApiMayChange
object Counter {
  val empty: Counter = Counter(0)

  final case class Updated(delta: BigInt) {

    /**
     * JAVA API
     */
    def this(delta: java.math.BigInteger) = this(delta: BigInt)

    /**
     * JAVA API
     */
    def this(delta: Int) = this(delta: BigInt)
  }
}

@ApiMayChange
final case class Counter(value: BigInt) extends OpCrdt[Counter.Updated] {

  override type T = Counter

  override def applyOperation(event: Counter.Updated): Counter =
    copy(value = value + event.delta)
}
