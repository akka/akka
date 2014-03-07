package akka.performance.trading.system

import akka.performance.trading.domain.Order

final case class Rsp(order: Order, status: Boolean)
