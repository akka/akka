package se.scalablesolutions.akka.spring.foo;

import se.scalablesolutions.akka.actor.UntypedActor;
import se.scalablesolutions.akka.actor.ActorRef;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;


/**
 * test class
 */
public class PingActor extends UntypedActor implements ApplicationContextAware {

  private String stringFromVal;
  private String stringFromRef;

  private boolean gotApplicationContext = false;


  public void setApplicationContext(ApplicationContext context) {
    gotApplicationContext = true;
  }

  public boolean gotApplicationContext() {
    return gotApplicationContext;
  }

  public String getStringFromVal() {
    return stringFromVal;
  }

  public void setStringFromVal(String s) {
    stringFromVal = s;
  }

  public String getStringFromRef() {
    return stringFromRef;
  }

  public void setStringFromRef(String s) {
    stringFromRef = s;
  }


  private String longRunning() {
    try {
      Thread.sleep(6000);
    } catch (InterruptedException e) {
    }
    return "this took long";
  }

  public void onReceive(Object message) throws Exception {
    if (message instanceof String) {
      System.out.println("Ping received String message: " + message);
      if (message.equals("longRunning")) {
        System.out.println("### starting pong");
        ActorRef pongActor = UntypedActor.actorOf(PongActor.class).start();
        pongActor.sendRequestReply("longRunning", getContext());
      }
    } else {
      throw new IllegalArgumentException("Unknown message: " + message);
    }
  }


}

