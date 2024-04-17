package com.example;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;

public class HelloWorldTest {
  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

 @Test
 public void testGreeting() {
  ActorRef<HelloWorld.Greet> underTest = testKit.spawn(HelloWorld.create());
  TestProbe<HelloWorld.Greeted> replyProbe = testKit.createTestProbe();
  underTest.tell(new HelloWorld.Greet("Santa", replyProbe.ref()));
  replyProbe.expectMessage(new HelloWorld.Greeted("Santa", underTest));
 }
}
