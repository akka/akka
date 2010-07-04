/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.jta

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.stm.TransactionContainer

import java.util.{List => JList}
import java.util.concurrent.CopyOnWriteArrayList

import javax.naming.{NamingException, Context, InitialContext}
import javax.transaction.{
  Transaction,
  UserTransaction,
  TransactionManager,
  Status,
  RollbackException,
  SystemException,
  Synchronization,
  TransactionRequiredException
}

/**
 * <p>
 * Trait that implements a JTA transaction service that obeys the transaction semantics defined
 * in the transaction attribute types for the transacted methods according to the EJB 3 draft specification.
 * The aspect handles UserTransaction, TransactionManager instance variable injection thru @javax.ejb.Inject
 * (name subject to change as per EJB 3 spec) and method transaction levels thru @javax.ejb.TransactionAttribute.
 * </p>
 *
 * <p>
 * This trait should be inherited to implement the getTransactionManager() method that should return a concrete
 * javax.transaction.TransactionManager implementation (from JNDI lookup etc).
 * </p>
 * <p>
 * <h3>Transaction attribute semantics</h3>
 * (From http://www.kevinboone.com/ejb-transactions.html)
 * </p>
 * <p>
 * <h4>Required</h4>
 * 'Required' is probably the best choice (at least initially) for an EJB method that will need to be transactional. In this case, if the  method's caller is already part of a transaction, then the EJB method does not create a new transaction, but continues in the same transaction as its caller. If the caller is not in a transaction, then a new transaction is created for the EJB method. If something happens in the EJB that means that a rollback is required, then the extent of the rollback will include everything done in the EJB method, whatever the condition of the caller. If the caller was in a transaction, then everything done by the caller will be rolled back as well. Thus the 'required' attribute ensures that any work done by the EJB will be rolled back if necessary, and if the caller requires a rollback that too will be rolled back.
 * </p>
 * <p>
 * <h4>RequiresNew</h4>
 * 'RequiresNew' will be appropriate if you want to ensure that the EJB method is rolled back if necessary, but you don't want the rollback to propogate back to the caller. This attribute results in the creation of a new transaction for the method, regardless of the transactional state of the caller. If the caller was operating in a transaction, then its transaction is suspended until the EJB method completes. Because a new transaction is always created, there may be a slight performance penalty if this attribute is over-used.
 * </p>
 * <p>
 * <h4>Mandatory</h4>
 * With the 'mandatory' attribute, the EJB method will not even start unless its caller is in a transaction. It will throw a <code>TransactionRequiredException</code> instead. If the method does start, then it will become part of the transaction of the caller. So if the EJB method signals a failure, the caller will be rolled back as well as the EJB.
 * </p>
 * <p>
 * <h4>Supports</h4>
 * With this attribute, the EJB method does not care about the transactional context of its caller. If the caller is part of a transaction, then the EJB method will be part of the same transaction. If the EJB method fails, the transaction will roll back. If the caller is not part of a transaction, then the EJB method will still operate, but a failure will not cause anything to roll back. 'Supports' is probably the attribute that leads to the fastest method call (as there is no transactional overhead), but it can lead to unpredicatable results. If you want a method to be isolated from transactions, that is, to have no effect on the transaction of its caller, then use 'NotSupported' instead.
 * </p>
 * <p>
 * <h4>NotSupported</h4>
 * With the 'NotSupported' attribute, the EJB method will never take part in a transaction. If the caller is part of a transaction, then the caller's transaction is suspended. If the EJB method fails, there will be no effect on the caller's transaction, and no rollback will occur. Use this method if you want to ensure that the EJB method will not cause a rollback in its caller. This is appropriate if, for example, the method does something non-essential, such as logging a message. It would not be helpful if the failure of this operation caused a transaction rollback.
 * </p>
 * <p>
 * <h4>Never</h4>
 * The 'NotSupported'' attribute will ensure that the EJB method is never called by a transactional caller. Any attempt to do so will result in a <code>RemoteException</code> being thrown. This attribute is probably less useful than `NotSupported', in that NotSupported will assure that the caller's transaction is never affected by the EJB method (just as `Never' does), but will allow a call from a transactional caller if necessary.
 * </p>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionProtocol extends Logging {

  protected val synchronization: JList[Synchronization] = new CopyOnWriteArrayList[Synchronization]
  protected val joinTransactionFuns: JList[() => Unit] = new CopyOnWriteArrayList[() => Unit]
  protected val exceptionsNotToRollbackOn: JList[Class[_ <: Exception]] = new CopyOnWriteArrayList[Class[_ <: Exception]]

  def joinTransaction(): Unit = {
    val it = joinTransactionFuns.iterator
    while (it.hasNext) {
      val fn = it.next
      fn()
    }
  }

  def handleException(tm: TransactionContainer, e: Exception) = {
    var rollback = true
    val it = joinTransactionFuns.iterator
    while (it.hasNext) {
      val exception = it.next
      if (e.getClass.isAssignableFrom(exception.getClass))
      rollback = false
    }
    if (rollback) tm.setRollbackOnly
    throw e
  }

  /**
   * Wraps body in a transaction with REQUIRED semantics.
   * <p/>
   * Creates a new transaction if no transaction is active in scope, else joins the outer transaction.
   */
  def withTxRequired[T](body: => T): T = {
    val tm = TransactionContext.getTransactionContainer
    if (!isInExistingTransaction(tm)) {
      tm.begin
      registerSynchronization
      try {
        joinTransaction
        body
      } catch {
        case e: Exception => handleException(tm, e)
      } finally {
        commitOrRollBack(tm)
      }
    } else body
  }

  /**
   * Wraps body in a transaction with REQUIRES_NEW semantics.
   * <p/>
   * Suspends existing transaction, starts a new transaction, invokes body,
   * commits or rollbacks new transaction, finally resumes previous transaction.
   */
  def withTxRequiresNew[T](body: => T): T = TransactionContext.withNewContext {
    val tm = TransactionContext.getTransactionContainer
    tm.begin
    registerSynchronization
    try {
      joinTransaction
      body
    } catch {
      case e: Exception => handleException(tm, e)
    } finally {
      commitOrRollBack(tm)
    }
  }

  /**
   * Wraps body in a transaction with NOT_SUPPORTED semantics.
   * <p/>
   * Suspends existing transaction, invokes body, resumes transaction.
   */
  def withTxNotSupported[T](body: => T): T = TransactionContext.withNewContext {
    body
  }

  /**
   * Wraps body in a transaction with SUPPORTS semantics.
   * <p/>
   * Basicalla a No-op.
   */
  def withTxSupports[T](body: => T): T = {
    // attach to current if exists else skip -> do nothing
    body
  }

  /**
   * Wraps body in a transaction with MANDATORY semantics.
   * <p/>
   * Throws a TransactionRequiredException if there is no transaction active in scope.
   */
  def withTxMandatory[T](body: => T): T = {
    if (!isInExistingTransaction(TransactionContext.getTransactionContainer))
      throw new TransactionRequiredException("No active TX at method with TX type set to MANDATORY")
    body
  }

  /**
   * Wraps body in a transaction with NEVER semantics.
   * <p/>
   * Throws a SystemException in case of an existing transaction in scope.
   */
  def withTxNever[T](body: => T): T = {
    if (isInExistingTransaction(TransactionContext.getTransactionContainer))
      throw new SystemException("Detected active TX at method with TX type set to NEVER")
    body
  }

  protected def commitOrRollBack(tm: TransactionContainer) = {
    if (isInExistingTransaction(tm)) {
      if (isRollbackOnly(tm)) {
        log.debug("Rolling back TX marked as ROLLBACK_ONLY")
        tm.rollback
      } else {
        log.debug("Committing TX")
        tm.commit
      }
    }
  }

  // ---------------------------
  // Helper methods
  // ---------------------------

  protected def registerSynchronization = {
    val it = synchronization.iterator
    while (it.hasNext) TransactionContext.getTransactionContainer.registerSynchronization(it.next)
  }
  /**
   * Checks if a transaction is an existing transaction.
   *
   * @param tm the transaction manager
   * @return boolean
   */
  protected def isInExistingTransaction(tm: TransactionContainer): Boolean =
    tm.getStatus != Status.STATUS_NO_TRANSACTION

  /**
   * Checks if current transaction is set to rollback only.
   *
   * @param tm the transaction manager
   * @return boolean
   */
  protected def isRollbackOnly(tm: TransactionContainer): Boolean =
    tm.getStatus == Status.STATUS_MARKED_ROLLBACK

  /**
   * A ThreadLocal variable where to store suspended TX and enable pay as you go
   * before advice - after advice data sharing in a specific case of requiresNew TX
   */
  private val suspendedTx = new ThreadLocal[Transaction] {
    override def initialValue = null
  }

  private def storeInThreadLocal(tx: Transaction) = suspendedTx.set(tx)

  private def fetchFromThreadLocal: Option[Transaction] = {
    if (suspendedTx != null && suspendedTx.get() != null) Some(suspendedTx.get.asInstanceOf[Transaction])
    else None
  }
}
