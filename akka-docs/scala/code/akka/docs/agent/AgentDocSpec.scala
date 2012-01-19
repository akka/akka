/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.agent

import akka.agent.Agent
import akka.util.duration._
import akka.util.Timeout
import akka.testkit._

class AgentDocSpec extends AkkaSpec {

  "create and close" in {
    //#create
    import akka.agent.Agent

    val agent = Agent(5)
    //#create

    //#close
    agent.close()
    //#close
  }

  "create with implicit system" in {
    //#create-implicit-system
    import akka.actor.ActorSystem
    import akka.agent.Agent

    implicit val system = ActorSystem("app")

    val agent = Agent(5)
    //#create-implicit-system

    agent.close()
    system.shutdown()
  }

  "create with explicit system" in {
    //#create-explicit-system
    import akka.actor.ActorSystem
    import akka.agent.Agent

    val system = ActorSystem("app")

    val agent = Agent(5)(system)
    //#create-explicit-system

    agent.close()
    system.shutdown()
  }

  "send and sendOff" in {
    val agent = Agent(0)

    //#send
    // send a value
    agent send 7

    // send a function
    agent send (_ + 1)
    agent send (_ * 2)
    //#send

    def longRunningOrBlockingFunction = (i: Int) ⇒ i * 1

    //#send-off
    // sendOff a function
    agent sendOff (longRunningOrBlockingFunction)
    //#send-off

    val result = agent.await(Timeout(5 seconds))
    result must be === 16
  }

  "read with apply" in {
    val agent = Agent(0)

    //#read-apply
    val result = agent()
    //#read-apply

    result must be === 0
  }

  "read with get" in {
    val agent = Agent(0)

    //#read-get
    val result = agent.get
    //#read-get

    result must be === 0
  }

  "read with await" in {
    val agent = Agent(0)

    //#read-await
    import akka.util.duration._
    import akka.util.Timeout

    implicit val timeout = Timeout(5 seconds)
    val result = agent.await
    //#read-await

    result must be === 0
  }

  "read with future" in {
    val agent = Agent(0)

    //#read-future
    import akka.dispatch.Await

    implicit val timeout = Timeout(5 seconds)
    val future = agent.future
    val result = Await.result(future, timeout.duration)
    //#read-future

    result must be === 0
  }

  "transfer example" in {
    //#transfer-example
    import akka.agent.Agent
    import akka.util.duration._
    import akka.util.Timeout
    import scala.concurrent.stm._

    def transfer(from: Agent[Int], to: Agent[Int], amount: Int): Boolean = {
      atomic { txn ⇒
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

    implicit val timeout = Timeout(5 seconds)
    val fromValue = from.await // -> 50
    val toValue = to.await // -> 70
    //#transfer-example

    fromValue must be === 50
    toValue must be === 70
  }

  "monadic example" in {
    //#monadic-example
    val agent1 = Agent(3)
    val agent2 = Agent(5)

    // uses foreach
    var result = 0
    for (value ← agent1) {
      result = value + 1
    }

    // uses map
    val agent3 = for (value ← agent1) yield value + 1

    // or using map directly
    val agent4 = agent1 map (_ + 1)

    // uses flatMap
    val agent5 = for {
      value1 ← agent1
      value2 ← agent2
    } yield value1 + value2
    //#monadic-example

    result must be === 4
    agent3() must be === 4
    agent4() must be === 4
    agent5() must be === 8

    agent1.close()
    agent2.close()
    agent3.close()
    agent4.close()
    agent5.close()
  }
}
