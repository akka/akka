/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.util

object Address {
  def apply(hostname: String, port: Int) = new Address(hostname, port)
}

class Address(val hostname: String, val port: Int) {
  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, hostname)
    result = HashCode.hash(result, port)
    result
  }

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[Address] &&
        that.asInstanceOf[Address].hostname == hostname &&
        that.asInstanceOf[Address].port == port
  }
}
