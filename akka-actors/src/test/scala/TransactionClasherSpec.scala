package se.scalablesolutions.akka.actor

import junit.framework.TestCase

import stm.TransactionRollbackException
import org.junit.{Test, Before}
import org.junit.Assert._

import state.TransactionalState

class TxActor(clasher: Actor) extends Actor {
  timeout = 1000000
  makeTransactionRequired

  def receive: PartialFunction[Any, Unit] = {
    case msg: AnyRef =>
      clasher !! msg
      reply(msg)
  }
}

class TxClasherActor extends Actor {
  val vector = TransactionalState.newVector[String]
  timeout = 1000000
  makeTransactionRequired
  var count = 0
  def receive: PartialFunction[Any, Unit] = {
    case "First" =>
      if (count == 0) Thread.sleep(5000)
      count += 1
      println("FIRST")
      vector.add("First")
      println("--- VECTOR: " + vector)
      reply("First")
    case "Second" =>
      println("SECOND")
      vector.add("Second")
      println("--- VECTOR: " + vector)
      reply("Second")
    case "Index0" =>
      reply(vector(0))
    case "Index1" =>
      reply(vector(1))
  }
}

class TxActorOneWay(clasher: Actor) extends Actor {
  timeout = 1000000
  makeTransactionRequired

  def receive: PartialFunction[Any, Unit] = {
    case msg: AnyRef =>
      clasher ! msg
  }
}

class TxClasherActorOneWay extends Actor {
  val vector = TransactionalState.newVector[String]
  timeout = 1000000
  makeTransactionRequired
  var count = 0
  def receive: PartialFunction[Any, Unit] = {
    case "First" =>
      if (count == 0) Thread.sleep(5000)
      count += 1
      println("FIRST")
      vector.add("First")
      println("--- VECTOR: " + vector)
    case "Second" =>
      println("SECOND")
      vector.add("Second")
      println("--- VECTOR: " + vector)
    case "Index0" =>
      reply(vector(0))
    case "Index1" =>
      reply(vector(1))
  }
}

class TransactionClasherSpec extends TestCase {
  @Test
  def testBangBangClash = {
    val clasher = new TxClasherActor
    clasher.start
    val txActor1 = new TxActor(clasher)
    txActor1.start
    val txActor2 = new TxActor(clasher)
    txActor2.start

    val t1 = new Thread(new Runnable() {
      def run = {
        txActor1 !! "First"
      }
    }).start
    Thread.sleep(1000)
    try {
      txActor2 !! "Second"
      fail("Expected TransactionRollbackException")
    } catch { case e: TransactionRollbackException => {} }
  }

  @Test
  def testBangClash = {
    val clasher = new TxClasherActorOneWay
    clasher.start
    val txActor1 = new TxActorOneWay(clasher)
    txActor1.start
    val txActor2 = new TxActorOneWay(clasher)
    txActor2.start

    val t1 = new Thread(new Runnable() {
      def run = {
        txActor1 ! "First"
      }
    }).start
    Thread.sleep(1000)
    try {
      txActor2 ! "Second"
      fail("Expected TransactionRollbackException")
    } catch { case e: TransactionRollbackException => {} }
  }
 
  /*
  @Test
  def testX = {
    val clasher = new TxClasherActor
    clasher.start
    val txActor1 = new TxActor(clasher)
    txActor1.start
    val txActor2 = new TxActor(clasher)
    txActor2.start

    val t1 = new Thread(new Runnable() {
      def run = {
        txActor1 !! "First"
      }
    }).start
    Thread.sleep(1000)
    val res2 = txActor2 !! "Second"
    Thread.sleep(10000)
    assertEquals("Second", (clasher !! "Index0").get)
    assertEquals("First", (clasher !! "Index1").get)
  }
  */
}
