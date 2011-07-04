package akka.performance.trading.common

import akka.performance.trading.domain.Order

class TxLogDummy extends TxLog {

  def storeTx(order: Order) {
  }

  def close() {
  }

}
