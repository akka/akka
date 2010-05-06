package se.scalablesolutions.akka.persistence.redis

import org.junit.{Test, Before}
import org.junit.Assert._

import se.scalablesolutions.akka.actor.{Actor, ActorRef, Transactor}
import Actor._

/**
 * A persistent actor based on Redis storage.
 * <p/>
 * Demonstrates a bank account operation consisting of messages that:
 * <li>checks balance <tt>Balance</tt></li>
 * <li>debits amount<tt>Debit</tt></li>
 * <li>debits multiple amounts<tt>MultiDebit</tt></li>
 * <li>credits amount<tt>Credit</tt></li>
 * <p/>
 * Needs a running Redis server.
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */

case class Balance(accountNo: String)
case class Debit(accountNo: String, amount: BigInt, failer: ActorRef)
case class MultiDebit(accountNo: String, amounts: List[BigInt], failer: ActorRef)
case class Credit(accountNo: String, amount: BigInt)
case class Log(start: Int, finish: Int)
case object LogSize

class AccountActor extends Transactor {
  private lazy val accountState = RedisStorage.newMap
  private lazy val txnLog = RedisStorage.newVector
  //timeout = 5000

  def receive = {
    // check balance
    case Balance(accountNo) =>
      txnLog.add("Balance:%s".format(accountNo).getBytes)
      reply(BigInt(new String(accountState.get(accountNo.getBytes).get)))

    // debit amount: can fail
    case Debit(accountNo, amount, failer) =>
      txnLog.add("Debit:%s %s".format(accountNo, amount.toString).getBytes)

      val m: BigInt =
      accountState.get(accountNo.getBytes) match {
        case Some(bytes) => BigInt(new String(bytes))
        case None => 0
      }
      accountState.put(accountNo.getBytes, (m - amount).toString.getBytes)
      if (amount > m)
        failer !! "Failure"
      reply(m - amount)

    // many debits: can fail
    // demonstrates true rollback even if multiple puts have been done
    case MultiDebit(accountNo, amounts, failer) =>
      txnLog.add("MultiDebit:%s %s".format(accountNo, amounts.map(_.intValue).foldLeft(0)(_ + _).toString).getBytes)

      val m: BigInt =
      accountState.get(accountNo.getBytes) match {
        case Some(bytes) => BigInt(new String(bytes))
        case None => 0
      }
      var bal: BigInt = 0
      amounts.foreach {amount =>
        bal = bal + amount
        accountState.put(accountNo.getBytes, (m - bal).toString.getBytes)
      }
      if (bal > m) failer !! "Failure"
      reply(m - bal)

    // credit amount
    case Credit(accountNo, amount) =>
      txnLog.add("Credit:%s %s".format(accountNo, amount.toString).getBytes)

      val m: BigInt =
      accountState.get(accountNo.getBytes) match {
        case Some(bytes) => BigInt(new String(bytes))
        case None => 0
      }
      accountState.put(accountNo.getBytes, (m + amount).toString.getBytes)
      reply(m + amount)

    case LogSize =>
      reply(txnLog.length.asInstanceOf[AnyRef])

    case Log(start, finish) =>
      reply(txnLog.slice(start, finish))
  }
}

@serializable class PersistentFailerActor extends Transactor {
  // timeout = 5000
  def receive = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

import org.scalatest.junit.JUnitSuite
class RedisPersistentActorSpec extends JUnitSuite {
  @Test
  def testSuccessfulDebit = {
    val bactor = newActor[AccountActor]
    bactor.start
    val failer = newActor[PersistentFailerActor]
    failer.start
    bactor !! Credit("a-123", 5000)
    bactor !! Debit("a-123", 3000, failer)
    assertEquals(BigInt(2000), (bactor !! Balance("a-123")).get)

    bactor !! Credit("a-123", 7000)
    assertEquals(BigInt(9000), (bactor !! Balance("a-123")).get)

    bactor !! Debit("a-123", 8000, failer)
    assertEquals(BigInt(1000), (bactor !! Balance("a-123")).get)

    val c: Int = (bactor !! LogSize).get
    assertTrue(7 == c)
    import scala.collection.mutable.ArrayBuffer
    assert((bactor !! Log(0, 7)).get.asInstanceOf[ArrayBuffer[String]].size == 7)
    assert((bactor !! Log(0, 0)).get.asInstanceOf[ArrayBuffer[String]].size == 0)
    assert((bactor !! Log(1, 2)).get.asInstanceOf[ArrayBuffer[String]].size == 1)
    assert((bactor !! Log(6, 7)).get.asInstanceOf[ArrayBuffer[String]].size == 1)
    assert((bactor !! Log(0, 1)).get.asInstanceOf[ArrayBuffer[String]].size == 1)
  }

  @Test
  def testUnsuccessfulDebit = {
    val bactor = newActor[AccountActor]
    bactor.start
    bactor !! Credit("a-123", 5000)
    assertEquals(BigInt(5000), (bactor !! Balance("a-123")).get)

    val failer = newActor[PersistentFailerActor]
    failer.start
    try {
      bactor !! Debit("a-123", 7000, failer)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(BigInt(5000), (bactor !! Balance("a-123")).get)

    // should not count the failed one
    val c: Int = (bactor !! LogSize).get
    assertTrue(3 == c)
  }

  @Test
  def testUnsuccessfulMultiDebit = {
    val bactor = newActor[AccountActor]
    bactor.start
    bactor !! Credit("a-123", 5000)

    assertEquals(BigInt(5000), (bactor !! (Balance("a-123"), 5000)).get)

    val failer = newActor[PersistentFailerActor]
    failer.start
    try {
      bactor !! MultiDebit("a-123", List(500, 2000, 1000, 3000), failer)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(BigInt(5000), (bactor !! (Balance("a-123"), 5000)).get)

    // should not count the failed one
    val c: Int = (bactor !! LogSize).get
    assertTrue(3 == c)
  }
}
