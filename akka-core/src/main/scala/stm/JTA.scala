/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import javax.transaction.{TransactionManager, UserTransaction, 
                          Transaction => JtaTransaction, SystemException, 
                          Status, Synchronization, TransactionSynchronizationRegistry}
import javax.naming.{InitialContext, Context, NamingException}

import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.util.Logging

/**
 * Detects if there is a UserTransaction or TransactionManager available in the JNDI.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionContainer extends Logging {
  val AKKA_JTA_TRANSACTION_SERVICE_CLASS =                "se.scalablesolutions.akka.jta.AtomikosTransactionService"
  val DEFAULT_USER_TRANSACTION_NAME =                     "java:comp/UserTransaction"
  val FALLBACK_TRANSACTION_MANAGER_NAMES =                "java:comp/TransactionManager" ::
                                                          "java:appserver/TransactionManager" ::
                                                          "java:pm/TransactionManager" ::
                                                          "java:/TransactionManager" :: Nil
  val DEFAULT_TRANSACTION_SYNCHRONIZATION_REGISTRY_NAME = "java:comp/TransactionSynchronizationRegistry"

  val JTA_PROVIDER = config.getString("akka.jta.provider", "from-jndi")

  private var synchronizationRegistry: Option[TransactionSynchronizationRegistry] = None

  def apply(tm: Either[Option[UserTransaction], Option[TransactionManager]]) = new TransactionContainer(tm)

  def apply(): TransactionContainer =
    JTA_PROVIDER match {
      case "from-jndi" =>
        new TransactionContainer(findUserTransaction match {
          case None => Right(findTransactionManager)
          case tm =>   Left(tm)
        })
      case "atomikos" =>
        try {
          Class.forName(AKKA_JTA_TRANSACTION_SERVICE_CLASS)
               .newInstance.asInstanceOf[TransactionService]
               .transactionContainer
        } catch {
          case e: ClassNotFoundException =>
            throw new StmConfigurationException(
              "JTA provider defined as 'atomikos', but the AtomikosTransactionService classes can not be found." +
              "\n\tPlease make sure you have 'akka-jta' JAR and its dependencies on your classpath.")
        }
      case _ =>
        throw new StmConfigurationException(
        "No UserTransaction on TransactionManager could be found in scope." +
        "\n\tEither add 'akka-jta' to the classpath or make sure there is a" +
        "\n\tTransactionManager or UserTransaction defined in the JNDI.")

    }

  def findUserTransaction: Option[UserTransaction] = {
    val located = createInitialContext.lookup(DEFAULT_USER_TRANSACTION_NAME)
    if (located eq null) None
    else {
      log.info("JTA UserTransaction detected [%s]", located)
      Some(located.asInstanceOf[UserTransaction])
    }
  }

  def findSynchronizationRegistry: Option[TransactionSynchronizationRegistry] = synchronized {
    if (synchronizationRegistry.isDefined) synchronizationRegistry
    else {
      val located = createInitialContext.lookup(DEFAULT_TRANSACTION_SYNCHRONIZATION_REGISTRY_NAME)
      if (located eq null) None
      else {
        log.info("JTA TransactionSynchronizationRegistry detected [%s]", located)
        synchronizationRegistry = Some(located.asInstanceOf[TransactionSynchronizationRegistry])
        synchronizationRegistry
      }
    }
  }

  def findTransactionManager: Option[TransactionManager] = {
    val context = createInitialContext
    val tms = for {
      name <- FALLBACK_TRANSACTION_MANAGER_NAMES
      tm = context.lookup(name)
      if tm ne null
    } yield tm
    tms match {
      case Nil => None
      case tm :: _ =>
        log.info("JTA TransactionManager detected [%s]", tm)
        Some(tm.asInstanceOf[TransactionManager])
    }
  }

  private def createInitialContext = new InitialContext(new java.util.Hashtable)
}

/**
 * JTA transaction container holding either a UserTransaction or a TransactionManager.
 * <p/>
 * The TransactionContainer is created using the factory <tt>val container = TransactionContainer()</tt>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionContainer private (val tm: Either[Option[UserTransaction], Option[TransactionManager]]) {

  def registerSynchronization(sync: Synchronization) = {
    TransactionContainer.findSynchronizationRegistry match { // try to use SynchronizationRegistry in JNDI
      case Some(registry) =>
        registry.asInstanceOf[TransactionSynchronizationRegistry].registerInterposedSynchronization(sync)
      case None =>
        tm match {
          case Right(Some(txMan)) => // try to use TransactionManager
            txMan.getTransaction.registerSynchronization(sync)
          case _ =>
            log.warning("Cannot find TransactionSynchronizationRegistry in JNDI, can't register STM synchronization")
        }
    }
  }

  def begin = {
    TransactionContainer.log.ifTrace("Starting JTA transaction")
    tm match {
      case Left(Some(userTx)) => userTx.begin
      case Right(Some(txMan)) => txMan.begin
      case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
    }
  }

  def commit = {
    TransactionContainer.log.ifTrace("Committing JTA transaction")
    tm match {
      case Left(Some(userTx)) => userTx.commit
      case Right(Some(txMan)) => txMan.commit
      case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
    }
  }

  def rollback = {
    TransactionContainer.log.ifTrace("Aborting JTA transaction")
    tm match {
      case Left(Some(userTx)) => userTx.rollback
      case Right(Some(txMan)) => txMan.rollback
      case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
    }
  }

  def getStatus = tm match {
    case Left(Some(userTx)) => userTx.getStatus
    case Right(Some(txMan)) => txMan.getStatus
    case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
  }

  def isInExistingTransaction = tm match {
    case Left(Some(userTx)) => userTx.getStatus == Status.STATUS_ACTIVE
    case Right(Some(txMan)) => txMan.getStatus == Status.STATUS_ACTIVE
    case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
  }

  def isRollbackOnly = tm match {
    case Left(Some(userTx)) => userTx.getStatus == Status.STATUS_MARKED_ROLLBACK
    case Right(Some(txMan)) => txMan.getStatus == Status.STATUS_MARKED_ROLLBACK
    case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
  }

  def setRollbackOnly = tm match {
    case Left(Some(userTx)) => userTx.setRollbackOnly
    case Right(Some(txMan)) => txMan.setRollbackOnly
    case _ => throw new StmConfigurationException("Does not have a UserTransaction or TransactionManager in scope")
  }

  def suspend = tm match {
    case Right(Some(txMan)) => txMan.suspend
    case _ => throw new StmConfigurationException("Does not have a TransactionManager in scope")
  }

  def resume(tx: JtaTransaction) = tm match {
    case Right(Some(txMan)) => txMan.resume(tx)
    case _ => throw new StmConfigurationException("Does not have a TransactionManager in scope")
  }
}

/**
 * STM Synchronization class for synchronizing with the JTA TransactionManager.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class StmSynchronization(tc: TransactionContainer, tx: Transaction) extends Synchronization with Logging {
  def beforeCompletion = {
    val status = tc.getStatus
    if (status != Status.STATUS_ROLLEDBACK &&
        status != Status.STATUS_ROLLING_BACK &&
        status != Status.STATUS_MARKED_ROLLBACK) {
      log.debug("JTA transaction has failed, abort STM transaction")
      tx.transaction.foreach(_.abort) // abort multiverse tx
    }
  }

  def afterCompletion(status: Int) = {}
}

/**
 * JTA Transaction service.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionService {
  def transactionContainer: TransactionContainer
}

