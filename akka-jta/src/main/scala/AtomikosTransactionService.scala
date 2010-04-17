/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.jta

import javax.transaction.{TransactionManager, SystemException}

import com.atomikos.icatch.jta.{J2eeTransactionManager, J2eeUserTransaction}
import com.atomikos.icatch.config.{TSInitInfo, UserTransactionService, UserTransactionServiceImp}

import se.scalablesolutions.akka.config.Config._

/**
 * Atomikos implementation of the transaction service trait.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object AtomikosTransactionService extends TransactionService with TransactionProtocol {

  val JTA_TRANSACTION_TIMEOUT = config.getInt("akka.jta.timeout", 60)
  private val txService: UserTransactionService = new UserTransactionServiceImp
  private val info: TSInitInfo = txService.createTSInitInfo

  val transactionManager =
    try {
      txService.init(info)
      val tm: TransactionManager = new J2eeTransactionManager
      tm.setTransactionTimeout(JTA_TRANSACTION_TIMEOUT)
      tm
    } catch {
      case e => throw new SystemException("Could not create a new Atomikos J2EE Transaction Manager, due to: " + e.toString)
    }

  // TODO: gracefully shutdown of the TM
  //txService.shutdown(false)
}
