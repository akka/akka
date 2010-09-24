package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.actor.{Transactor, Actor, ActorRef}
import Actor._
import BankAccountActor._


case class Balance(accountNo: String)
case class Debit(accountNo: String, amount: Int, failer: ActorRef)
case class MultiDebit(accountNo: String, amounts: List[Int], failer: ActorRef)
case class Credit(accountNo: String, amount: Int)
case class Log(start: Int, finish: Int)
case object LogSize

object BankAccountActor {
  val state = "accountState"
  val tx = "txnLog"
}

class BankAccountActor extends Transactor {
  private  val accountState = VoldemortStorage.newMap(state)
  private  val txnLog = VoldemortStorage.newVector(tx)

  import sjson.json.DefaultProtocol._
  import sjson.json.JsonSerialization._

  def receive: Receive = {
    // check balance
    case Balance(accountNo) =>
      txnLog.add(("Balance:" + accountNo).getBytes)
      self.reply(
        accountState.get(accountNo.getBytes)
                .map(frombinary[Int](_))
                .getOrElse(0))

    // debit amount: can fail
    case Debit(accountNo, amount, failer) =>
      txnLog.add(("Debit:" + accountNo + " " + amount).getBytes)
      val m = accountState.get(accountNo.getBytes)
              .map(frombinary[Int](_))
              .getOrElse(0)

      accountState.put(accountNo.getBytes, tobinary(m - amount))
      if (amount > m) failer !! "Failure"

      self.reply(m - amount)

    // many debits: can fail
    // demonstrates true rollback even if multiple puts have been done
    case MultiDebit(accountNo, amounts, failer) =>
      val sum = amounts.foldRight(0)(_ + _)
      txnLog.add(("MultiDebit:" + accountNo + " " + sum).getBytes)

      val m = accountState.get(accountNo.getBytes)
              .map(frombinary[Int](_))
              .getOrElse(0)

      var cbal = m
      amounts.foreach {
        amount =>
          accountState.put(accountNo.getBytes, tobinary(m - amount))
          cbal = cbal - amount
          if (cbal < 0) failer !! "Failure"
      }

      self.reply(m - sum)

    // credit amount
    case Credit(accountNo, amount) =>
      txnLog.add(("Credit:" + accountNo + " " + amount).getBytes)
      val m = accountState.get(accountNo.getBytes)
              .map(frombinary[Int](_))
              .getOrElse(0)

      accountState.put(accountNo.getBytes, tobinary(m + amount))

      self.reply(m + amount)

    case LogSize =>
      self.reply(txnLog.length)

    case Log(start, finish) =>
      self.reply(txnLog.slice(start, finish).map(new String(_)))
  }
}

@serializable class PersistentFailerActor extends Transactor {
  def receive = {
    case "Failure" =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }
}

@RunWith(classOf[JUnitRunner])
class VoldemortPersistentActorSuite extends
Spec with
        ShouldMatchers with
        BeforeAndAfterEach with EmbeddedVoldemort {
  import VoldemortStorageBackend._


  override def beforeEach {
    removeMapStorageFor(state)
    var size = getVectorStorageSizeFor(tx)
    (0 to size).foreach {
      index => {
        vectorValueClient.delete(getVectorValueKey(tx, index))
      }
    }
    vectorSizeClient.delete(tx)
  }

  override def afterEach {
    beforeEach
  }

  describe("successful debit") {
    it("should debit successfully") {
      log.info("Succesful Debit starting")
      val bactor = actorOf[BankAccountActor]
      bactor.start
      val failer = actorOf[PersistentFailerActor]
      failer.start
      bactor !! Credit("a-123", 5000)
      log.info("credited")
      bactor !! Debit("a-123", 3000, failer)
      log.info("debited")
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(2000)
      log.info("balane matched")
      bactor !! Credit("a-123", 7000)
      log.info("Credited")
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(9000)
      log.info("Balance matched")
      bactor !! Debit("a-123", 8000, failer)
      log.info("Debited")
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(1000)
      log.info("Balance matched")
      (bactor !! LogSize).get.asInstanceOf[Int] should equal(7)
      (bactor !! Log(0, 7)).get.asInstanceOf[Iterable[String]].size should equal(7)
    }
  }

  describe("unsuccessful debit") {
    it("debit should fail") {
      val bactor = actorOf[BankAccountActor]
      bactor.start
      val failer = actorOf[PersistentFailerActor]
      failer.start
      bactor !! Credit("a-123", 5000)
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      evaluating {
        bactor !! Debit("a-123", 7000, failer)
      } should produce[Exception]
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      (bactor !! LogSize).get.asInstanceOf[Int] should equal(3)
    }
  }

  describe("unsuccessful multidebit") {
    it("multidebit should fail") {
      val bactor = actorOf[BankAccountActor]
      bactor.start
      val failer = actorOf[PersistentFailerActor]
      failer.start
      bactor !! Credit("a-123", 5000)
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      evaluating {
        bactor !! MultiDebit("a-123", List(1000, 2000, 4000), failer)
      } should produce[Exception]
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      (bactor !! LogSize).get.asInstanceOf[Int] should equal(3)
    }
  }
}
