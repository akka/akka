/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.jta

import javax.transaction.{TransactionManager, SystemException}

import com.atomikos.icatch.jta.{J2eeTransactionManager, J2eeUserTransaction}
import com.atomikos.icatch.config.{TSInitInfo, UserTransactionService, UserTransactionServiceImp}

import akka.config.Config._
import akka.util.Duration

object AtomikosTransactionService extends AtomikosTransactionService

/**
 * Atomikos implementation of the transaction service trait.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AtomikosTransactionService extends TransactionService with TransactionProtocol {
  val JTA_TRANSACTION_TIMEOUT = Duration(config.getInt("akka.jta.timeout", 60), TIME_UNIT)

  private val txService: UserTransactionService = new UserTransactionServiceImp
  private val info: TSInitInfo = txService.createTSInitInfo

  val transactionContainer: TransactionContainer = TransactionContainer(Right(Some(
    try {
      txService.init(info)
      val tm: TransactionManager = new J2eeTransactionManager
      tm.setTransactionTimeout(JTA_TRANSACTION_TIMEOUT.toSeconds.toInt)
      tm
    } catch {
      case e => throw new SystemException(
        "Could not create a new Atomikos J2EE Transaction Manager, due to: " + e.toString)
    }
  )))
  // TODO: gracefully postStop of the TM
  //txService.postStop(false)
}
