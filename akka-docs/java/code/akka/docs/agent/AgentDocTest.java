/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.agent;

import static org.junit.Assert.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import akka.testkit.AkkaSpec;

//#import-system
import akka.actor.ActorSystem;
//#import-system

//#import-agent
import akka.agent.Agent;
//#import-agent

//#import-function
import akka.japi.Function;
//#import-function

//#import-timeout
import akka.util.Timeout;
import static java.util.concurrent.TimeUnit.SECONDS;
//#import-timeout

public class AgentDocTest {

  private static ActorSystem testSystem;

  @BeforeClass
  public static void beforeAll() {
    testSystem = ActorSystem.create("AgentDocTest", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    testSystem.shutdown();
    testSystem = null;
  }

  @Test
  public void createAndClose() {
        //#create
    ActorSystem system = ActorSystem.create("app");

    Agent<Integer> agent = new Agent<Integer>(5, system);
    //#create

    //#close
    agent.close();
    //#close

    system.shutdown();
  }

  @Test
  public void sendAndSendOffAndReadAwait() {
    Agent<Integer> agent = new Agent<Integer>(5, testSystem);

    //#send
    // send a value
    agent.send(7);

    // send a function
    agent.send(new Function<Integer, Integer>() {
      public Integer apply(Integer i) {
        return i * 2;
      }
    });
    //#send

    Function<Integer, Integer> longRunningOrBlockingFunction = new Function<Integer, Integer>() {
      public Integer apply(Integer i) {
        return i * 1;
      }
    };

    //#send-off
    // sendOff a function
    agent.sendOff(longRunningOrBlockingFunction);
    //#send-off

    //#read-await
    Integer result = agent.await(new Timeout(5, SECONDS));
    //#read-await

    assertEquals(result, new Integer(14));

    agent.close();
  }

  @Test
  public void readWithGet() {
    Agent<Integer> agent = new Agent<Integer>(5, testSystem);

    //#read-get
    Integer result = agent.get();
    //#read-get

    assertEquals(result, new Integer(5));

    agent.close();
  }
}