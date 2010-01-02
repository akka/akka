package se.scalablesolutions.akka.spring;

import org.springframework.transaction.annotation.Transactional;

import se.scalablesolutions.akka.state.TransactionalState;
import se.scalablesolutions.akka.state.TransactionalRef;
import se.scalablesolutions.akka.state.TransactionalVector;
import se.scalablesolutions.akka.state.TransactionalMap;

import se.scalablesolutions.akka.annotation.oneway;
import se.scalablesolutions.akka.annotation.prerestart;
import se.scalablesolutions.akka.annotation.postrestart;

public class MyService {

  private TransactionalMap<String, String> mapState;
  private TransactionalVector<String> vectorState;
  private TransactionalRef<String> refState;
  private boolean isInitialized = false;

  @Transactional
  public void init() {
    if (!isInitialized) {
      mapState = TransactionalState.newMap();
      vectorState = TransactionalState.newVector();
      refState = TransactionalState.newRef();
      isInitialized = true;
    }
  }

  public String getThreadName() {
    return Thread.currentThread().getName();
  }

  public Integer getNumbers(int aTestNumber) {
    return new Integer(aTestNumber);
  }
  
  @Transactional
  public String getMapState(String key) {
    return (String)mapState.get(key).get();
  }

  @Transactional
  public String getVectorState() {
    return (String)vectorState.last();
  }

  @Transactional
  public String getRefState() {
    return (String)refState.get().get();
  }

  @Transactional
  public void setMapState(String key, String msg) {
    mapState.put(key, msg);
  }

  @Transactional
  public void setVectorState(String msg) {
    vectorState.add(msg);
  }

  @Transactional
  public void setRefState(String msg) {
    refState.swap(msg);
  }

  @prerestart
  public void preRestart() {
    System.out.println("################ PRE RESTART");
  }

  @postrestart
  public void postRestart() {
    System.out.println("################ POST RESTART");
  }
}

