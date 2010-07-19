package se.scalablesolutions.akka.persistence.redis

import org.scalatest.junit.JUnitSuite

import org.junit.{Test, Before}
import org.junit.Assert._

import se.scalablesolutions.akka.actor.{ActorRef, Transactor}
import se.scalablesolutions.akka.actor.Actor._

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
case object LogSize

class AccountActor extends Transactor {
  import self._
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
  }
}

@serializable class PersistentFailerActor extends Transactor {
  // timeout = 5000
  def receive = {
    case "Failure" =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }
}

class RedisPersistentActorSpec extends JUnitSuite {
  @Test
  def testSuccessfulDebit = {
    val bactor = actorOf[AccountActor]
    bactor.start
    val failer = actorOf[PersistentFailerActor]
    failer.start
    bactor !! Credit("a-123", 5000)
    bactor !! Debit("a-123", 3000, failer)
    assertEquals(BigInt(2000), (bactor !! Balance("a-123")).get)

    bactor !! Credit("a-123", 7000)
    assertEquals(BigInt(9000), (bactor !! Balance("a-123")).get)

    bactor !! Debit("a-123", 8000, failer)
    assertEquals(BigInt(1000), (bactor !! Balance("a-123")).get)

    val c = (bactor !! LogSize).as[Int].get
    assertTrue(7 == c)
  }

  @Test
  def testUnsuccessfulDebit = {
    val bactor = actorOf[AccountActor]
    bactor.start
    bactor !! Credit("a-123", 5000)
    assertEquals(BigInt(5000), (bactor !! Balance("a-123")).get)

    val failer = actorOf[PersistentFailerActor]
    failer.start
    try {
      bactor !! Debit("a-123", 7000, failer)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(BigInt(5000), (bactor !! Balance("a-123")).get)

    // should not count the failed one
    val c = (bactor !! LogSize).as[Int].get
    assertTrue(3 == c)
  }

  @Test
  def testUnsuccessfulMultiDebit = {
    val bactor = actorOf[AccountActor]
    bactor.start
    bactor !! Credit("a-123", 5000)

    assertEquals(BigInt(5000), (bactor !! (Balance("a-123"), 5000)).get)

    val failer = actorOf[PersistentFailerActor]
    failer.start
    try {
      bactor !! MultiDebit("a-123", List(500, 2000, 1000, 3000), failer)
      fail("should throw exception")
    } catch { case e: RuntimeException => {}}

    assertEquals(BigInt(5000), (bactor !! (Balance("a-123"), 5000)).get)

    // should not count the failed one
    val c = (bactor !! LogSize).as[Int].get
    assertTrue(3 == c)
  }
}
