package akka.performance.trading.common

import akka.performance.trading.domain.Order
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.BufferedWriter

/**
 * Note that this is not thread safe, concurrency must be handled by caller.
 */
class TxLogFile(fileName: String) extends TxLog {
  private val txLogFile = new File(System.getProperty("java.io.tmpdir"), fileName)
  private var txLogWriter: BufferedWriter = null

  def storeTx(order: Order) {
    if (txLogWriter == null) {
      txLogWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(txLogFile)))
    }
    txLogWriter.write(order.toString)
    txLogWriter.write("\n")
    txLogWriter.flush()
  }

  def close() {
    if (txLogWriter != null) {
      txLogWriter.close()
      txLogWriter = null
    }
  }

}
