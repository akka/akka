/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.jta

import javax.transaction.{TransactionManager, UserTransaction, SystemException}
import javax.naming.{InitialContext, Context, NamingException}

import se.scalablesolutions.akka.config.Config._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionManagerDetector {
  val DEFAULT_USER_TRANSACTION_NAME = "java:comp/UserTransaction"
  val FALLBACK_TRANSACTION_MANAGER_NAMES = List("java:comp/TransactionManager", 
                                                "java:appserver/TransactionManager",
                                                "java:pm/TransactionManager", 
                                                "java:/TransactionManager")
  val DEFAULT_TRANSACTION_SYNCHRONIZATION_REGISTRY_NAME = "java:comp/TransactionSynchronizationRegistry"
  val TRANSACTION_SYNCHRONIZATION_REGISTRY_CLASS_NAME = "javax.transaction.TransactionSynchronizationRegistry"

  def findUserTransaction: Option[UserTransaction] = {
    val located = createInitialContext.lookup(DEFAULT_USER_TRANSACTION_NAME)
    if (located eq null) None
    else Some(located.asInstanceOf[UserTransaction])
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
      case tm :: _ => Some(tm.asInstanceOf[TransactionManager])
    }
  }

  private def createInitialContext = new InitialContext(new java.util.Hashtable)
}
