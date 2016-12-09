/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.agent

import language.postfixOps

import akka.agent.Agent
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import akka.testkit._
import scala.concurrent.Future

class AgentDocSpec extends AkkaSpec {
  "create" in {
    //#create
    import scala.concurrent.ExecutionContext.Implicits.global
    import akka.agent.Agent
    val agent = Agent(5)
    //#create
  }

  "read value" in {
    import scala.concurrent.ExecutionContext.Implicits.global
    val agent = Agent(0)

    {
      //#read-apply
      val result = agent()
      //#read-apply
      result should be(0)
    }
    {
      //#read-get
      val result = agent.get
      //#read-get
      result should be(0)
    }

    {
      //#read-future
      val future = agent.future
      //#read-future
      Await.result(future, 5 seconds) should be(0)
    }
  }

  "send and sendOff" in {
    val agent = Agent(0)(ExecutionContext.global)
    //#send
    // send a value, enqueues this change
    // of the value of the Agent
    agent send 7

    // send a function, enqueues this change
    // to the value of the Agent
    agent send (_ + 1)
    agent send (_ * 2)
    //#send

    def longRunningOrBlockingFunction = (i: Int) => i * 1 // Just for the example code
    def someExecutionContext() = scala.concurrent.ExecutionContext.Implicits.global // Just for the example code
    //#send-off
    // the ExecutionContext you want to run the function on
    implicit val ec = someExecutionContext()
    // sendOff a function
    agent sendOff longRunningOrBlockingFunction
    //#send-off

    Await.result(agent.future, 5 seconds) should be(16)
  }

  "alter and alterOff" in {
    val agent = Agent(0)(ExecutionContext.global)
    //#alter
    // alter a value
    val f1: Future[Int] = agent alter 7

    // alter a function
    val f2: Future[Int] = agent alter (_ + 1)
    val f3: Future[Int] = agent alter (_ * 2)
    //#alter

    def longRunningOrBlockingFunction = (i: Int) => i * 1 // Just for the example code
    def someExecutionContext() = ExecutionContext.global // Just for the example code

    //#alter-off
    // the ExecutionContext you want to run the function on
    implicit val ec = someExecutionContext()
    // alterOff a function
    val f4: Future[Int] = agent alterOff longRunningOrBlockingFunction
    //#alter-off

    Await.result(f4, 5 seconds) should be(16)
  }

  "transfer example" in {
    //#transfer-example
    import scala.concurrent.ExecutionContext.Implicits.global
    import akka.agent.Agent
    import scala.concurrent.duration._
    import scala.concurrent.stm._

    def transfer(from: Agent[Int], to: Agent[Int], amount: Int): Boolean = {
      atomic { txn =>
        if (from.get < amount) false
        else {
          from send (_ - amount)
          to send (_ + amount)
          true
        }
      }
    }

    val from = Agent(100)
    val to = Agent(20)
    val ok = transfer(from, to, 50)

    val fromValue = from.future // -> 50
    val toValue = to.future // -> 70
    //#transfer-example

    Await.result(fromValue, 5 seconds) should be(50)
    Await.result(toValue, 5 seconds) should be(70)
    ok should be(true)
  }

  "monadic example" in {
    def println(a: Any) = ()
    //#monadic-example
    import scala.concurrent.ExecutionContext.Implicits.global
    val agent1 = Agent(3)
    val agent2 = Agent(5)

    // uses foreach
    for (value <- agent1)
      println(value)

    // uses map
    val agent3 = for (value <- agent1) yield value + 1

    // or using map directly
    val agent4 = agent1 map (_ + 1)

    // uses flatMap
    val agent5 = for {
      value1 <- agent1
      value2 <- agent2
    } yield value1 + value2
    //#monadic-example

    agent3() should be(4)
    agent4() should be(4)
    agent5() should be(8)
  }
}
