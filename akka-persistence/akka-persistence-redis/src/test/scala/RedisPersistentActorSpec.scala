package akka.persistence.redis

import org.scalatest.junit.JUnitSuite

import org.junit.{Test, Before}
import org.junit.Assert._

import akka.actor.{Actor, ActorRef}
import akka.actor.Actor._
import akka.stm.local._

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
case class Debit(accountNo: String, amount: Int)
case class MultiDebit(accountNo: String, amounts: List[Int])
case class Credit(accountNo: String, amount: Int)
case object LogSize

class AccountActor extends Actor {
  private val accountState = RedisStorage.newMap
  private val txnLog = RedisStorage.newVector
  self.timeout = 100000

  def receive = { case message => atomic { atomicReceive(message) } }

  def atomicReceive: Receive = {
    // check balance
    case Balance(accountNo) =>
      txnLog.add("Balance:%s".format(accountNo).getBytes)
      self.reply(new String(accountState.get(accountNo.getBytes).get).toInt)

    // debit amount: can fail
    case Debit(accountNo, amount) =>
      txnLog.add("Debit:%s %s".format(accountNo, amount.toString).getBytes)

      val Some(m) = accountState.get(accountNo.getBytes).map(x => (new String(x)).toInt) orElse Some(0)
      accountState.put(accountNo.getBytes, (m - amount).toString.getBytes)
      if (amount > m) fail

      self.reply(m - amount)

    // many debits: can fail
    // demonstrates true rollback even if multiple puts have been done
    case MultiDebit(accountNo, amounts) =>
      txnLog.add("MultiDebit:%s %s".format(accountNo, amounts.map(_.intValue).foldLeft(0)(_ + _).toString).getBytes)

      val Some(m) = accountState.get(accountNo.getBytes).map(x => (new String(x)).toInt) orElse Some(0)
      var bal = 0
      amounts.foreach {amount =>
        bal = bal + amount
        accountState.put(accountNo.getBytes, (m - bal).toString.getBytes)
      }
      if (bal > m) fail

      self.reply(m - bal)

    // credit amount
    case Credit(accountNo, amount) =>
      txnLog.add("Credit:%s %s".format(accountNo, amount.toString).getBytes)

      val Some(m) = accountState.get(accountNo.getBytes).map(x => (new String(x)).toInt) orElse Some(0)
      accountState.put(accountNo.getBytes, (m + amount).toString.getBytes)
      self.reply(m + amount)

    case LogSize =>
      self.reply(txnLog.length.asInstanceOf[AnyRef])
  }

  def fail = throw new RuntimeException("Expected exception; to test fault-tolerance")
}

class RedisPersistentActorSpec extends JUnitSuite {
  @Test
  def testSuccessfulDebit = {
    val bactor = actorOf(new AccountActor)
    bactor.start
    bactor !! Credit("a-123", 5000)
    bactor !! Debit("a-123", 3000)
    assertEquals(2000, (bactor !! Balance("a-123")).get)

    bactor !! Credit("a-123", 7000)
    assertEquals(9000, (bactor !! Balance("a-123")).get)

    bactor !! Debit("a-123", 8000)
    assertEquals(1000, (bactor !! Balance("a-123")).get)

    val c = (bactor !! LogSize).as[Int].get
    assertTrue(7 == c)
  }

  @Test
  def testUnsuccessfulDebit = {
    val bactor = actorOf[AccountActor]
    bactor.start
    bactor !! Credit("a-123", 5000)
    assertEquals(5000, (bactor !! Balance("a-123")).get)

    try {
      bactor !! Debit("a-123", 7000)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(5000, (bactor !! Balance("a-123")).get)

    // should not count the failed one
    val c = (bactor !! LogSize).as[Int].get
    assertTrue(3 == c)
  }

  @Test
  def testUnsuccessfulMultiDebit = {
    val bactor = actorOf[AccountActor]
    bactor.start
    bactor !! Credit("a-123", 5000)

    assertEquals(5000, (bactor !! (Balance("a-123"), 5000)).get)

    try {
      bactor !! MultiDebit("a-123", List(500, 2000, 1000, 3000))
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(5000, (bactor !! (Balance("a-123"), 5000)).get)

    // should not count the failed one
    val c = (bactor !! LogSize).as[Int].get
    assertTrue(3 == c)
  }
}
