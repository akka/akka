package se.scalablesolutions.akka.kernel.actor


import junit.framework.TestCase
import org.junit.{Test, Before}
import org.junit.Assert._

import kernel.state.{MongoStorageConfig, TransactionalState}

/**
 * A persistent actor based on MongoDB storage.
 * <p/>
 * Demonstrates a bank account operation consisting of messages that:
 * <li>checks balance <tt>Balance</tt></li>
 * <li>debits amount<tt>Debit</tt></li>
 * <li>debits multiple amounts<tt>MultiDebit</tt></li>
 * <li>credits amount<tt>Credit</tt></li>
 * <p/>
 * Needs a running Mongo server.
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */

case class Balance(accountNo: String)
case class Debit(accountNo: String, amount: BigInt, failer: Actor)
case class MultiDebit(accountNo: String, amounts: List[BigInt], failer: Actor)
case class Credit(accountNo: String, amount: BigInt)
case object LogSize

class BankAccountActor extends Actor {
  makeTransactionRequired
  private val accountState = 
    TransactionalState.newPersistentMap(MongoStorageConfig())
  private val txnLog = 
    TransactionalState.newPersistentVector(MongoStorageConfig())

  def receive: PartialFunction[Any, Unit] = {
    // check balance
    case Balance(accountNo) =>
      txnLog.add("Balance:" + accountNo)
      reply(accountState.get(accountNo).get)

    // debit amount: can fail
    case Debit(accountNo, amount, failer) =>
      txnLog.add("Debit:" + accountNo + " " + amount)
      val m: BigInt =
      accountState.get(accountNo) match {
        case None => 0
        case Some(v) => BigInt(v.asInstanceOf[String])
      }
      accountState.put(accountNo, (m - amount))
      if (amount > m)
        failer !! "Failure"
      reply(m - amount)

    // many debits: can fail
    // demonstrates true rollback even if multiple puts have been done
    case MultiDebit(accountNo, amounts, failer) =>
      txnLog.add("MultiDebit:" + accountNo + " " + amounts.map(_.intValue).foldLeft(0)(_ + _))
      val m: BigInt =
      accountState.get(accountNo) match {
        case None => 0
        case Some(v) => BigInt(v.asInstanceOf[String])
      }
      var bal: BigInt = 0
      amounts.foreach {amount =>
        bal = bal + amount
        accountState.put(accountNo, (m - bal))
      }
      if (bal > m) failer !! "Failure"
      reply(m - bal)

    // credit amount
    case Credit(accountNo, amount) =>
      txnLog.add("Credit:" + accountNo + " " + amount)
      val m: BigInt =
      accountState.get(accountNo) match {
        case None => 0
        case Some(v) => BigInt(v.asInstanceOf[String])
      }
      accountState.put(accountNo, (m + amount))
      reply(m + amount)

    case LogSize =>
      reply(txnLog.length.asInstanceOf[AnyRef])
  }
}

@serializable class PersistentFailerActor extends Actor {
  makeTransactionRequired
  def receive: PartialFunction[Any, Unit] = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

class MongoPersistentActorSpec extends TestCase {
  @Test
  def testSuccessfulDebit = {
    val bactor = new BankAccountActor
    bactor.start
    val failer = new PersistentFailerActor
    failer.start
    bactor !! Credit("a-123", 5000)
    bactor !! Debit("a-123", 3000, failer)
    assertEquals(BigInt(2000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))

    bactor !! Credit("a-123", 7000)
    assertEquals(BigInt(9000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))

    bactor !! Debit("a-123", 8000, failer)
    assertEquals(BigInt(1000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))
    assertEquals(7, (bactor !! LogSize).get)
  }

  @Test
  def testUnsuccessfulDebit = {
    val bactor = new BankAccountActor
    bactor.start
    bactor !! Credit("a-123", 5000)
    assertEquals(BigInt(5000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))

    val failer = new PersistentFailerActor
    failer.start
    try {
      bactor !! Debit("a-123", 7000, failer)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(BigInt(5000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))

    // should not count the failed one
    assertEquals(3, (bactor !! LogSize).get)
  }

  @Test
  def testUnsuccessfulMultiDebit = {
    val bactor = new BankAccountActor
    bactor.start
    bactor !! Credit("a-123", 5000)
    assertEquals(BigInt(5000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))

    val failer = new PersistentFailerActor
    failer.start
    try {
      bactor !! MultiDebit("a-123", List(500, 2000, 1000, 3000), failer)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(BigInt(5000),
      BigInt((bactor !! Balance("a-123")).get.asInstanceOf[String]))

    // should not count the failed one
    assertEquals(3, (bactor !! LogSize).get)
  }
}
