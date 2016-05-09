/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.actor.Address

@SerialVersionUID(1L)
final case class UniqueAddress(address: Address, uid: Int) extends Ordered[UniqueAddress] {
  override def hashCode = uid

  def compare(that: UniqueAddress): Int = {
    val result = Address.addressOrdering.compare(this.address, that.address)
    if (result == 0) if (this.uid < that.uid) -1 else if (this.uid == that.uid) 0 else 1
    else result
  }
}
