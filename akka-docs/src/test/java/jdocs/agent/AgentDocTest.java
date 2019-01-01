/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.agent;

import static org.junit.Assert.*;

import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

//#import-agent
    import scala.concurrent.ExecutionContext;
    import akka.agent.Agent;
    import akka.dispatch.ExecutionContexts;
//#import-agent

//#import-function
    import akka.dispatch.Mapper;
//#import-function

//#import-future
    import scala.concurrent.Future;
//#import-future

public class AgentDocTest extends jdocs.AbstractJavaTest {

  private static ExecutionContext ec = ExecutionContexts.global();

  @Test
  public void createAndRead() throws Exception {
    //#create
    ExecutionContext ec = ExecutionContexts.global();
    Agent<Integer> agent = Agent.create(5, ec);
    //#create

    //#read-get
    Integer result = agent.get();
    //#read-get

    //#read-future
    Future<Integer> future = agent.future();
    //#read-future

    assertEquals(result, new Integer(5));
    assertEquals(Await.result(future, Duration.create(5,"s")), new Integer(5));
  }

  @Test
  public void sendAndSendOffAndReadAwait() throws Exception {
    Agent<Integer> agent = Agent.create(5, ec);

    //#send
    // send a value, enqueues this change
    // of the value of the Agent
    agent.send(7);

    // send a Mapper, enqueues this change
    // to the value of the Agent
    agent.send(new Mapper<Integer, Integer>() {
      public Integer apply(Integer i) {
        return i * 2;
      }
    });
    //#send

      Mapper<Integer, Integer> longRunningOrBlockingFunction = new Mapper<Integer, Integer>() {
      public Integer apply(Integer i) {
        return i * 1;
      }
    };

    ExecutionContext theExecutionContextToExecuteItIn = ec;
    //#send-off
    // sendOff a function
    agent.sendOff(longRunningOrBlockingFunction,
                  theExecutionContextToExecuteItIn);
    //#send-off

    assertEquals(Await.result(agent.future(), Duration.create(5,"s")), new Integer(14));
  }

    @Test
    public void alterAndAlterOff() throws Exception {
    Agent<Integer> agent = Agent.create(5, ec);

    //#alter
    // alter a value
    Future<Integer> f1 = agent.alter(7);

    // alter a function (Mapper)
    Future<Integer> f2 = agent.alter(new Mapper<Integer, Integer>() {
        public Integer apply(Integer i) {
            return i * 2;
        }
    });
    //#alter

    Mapper<Integer, Integer> longRunningOrBlockingFunction = new Mapper<Integer, Integer>() {
        public Integer apply(Integer i) {
            return i * 1;
        }
    };

    ExecutionContext theExecutionContextToExecuteItIn = ec;
    //#alter-off
    // alterOff a function (Mapper)
    Future<Integer> f3 = agent.alterOff(longRunningOrBlockingFunction,
                                        theExecutionContextToExecuteItIn);
    //#alter-off

    assertEquals(Await.result(f3, Duration.create(5,"s")), new Integer(14));
    }
}
