package se.scalablesolutions.akka.spring.foo;


import se.scalablesolutions.akka.stm.TransactionalMap;
import se.scalablesolutions.akka.stm.TransactionalVector;
import se.scalablesolutions.akka.stm.Ref;
import se.scalablesolutions.akka.actor.*;

public class StatefulPojo extends TypedActor {
    private TransactionalMap<String, String> mapState;
    private TransactionalVector<String> vectorState;
    private Ref<String> refState;
    private boolean isInitialized = false;

  @Override
  public void initTransactionalState() {
      if (!isInitialized) {
        mapState = new TransactionalMap();
        vectorState = new TransactionalVector();
        refState = new Ref();
        isInitialized = true;
      }
    }

  /*
    public String getMapState(String key) {
      return (String)mapState.get(key).get();
    }

    public String getVectorState() {
      return (String)vectorState.last();
    }

    public String getRefState() {
      return (String)refState.get().get();
    }

    public void setMapState(String key, String msg) {
      mapState.put(key, msg);
    }

    public void setVectorState(String msg) {
      vectorState.add(msg);
    }

    public void setRefState(String msg) {
      refState.swap(msg);
    }

    public boolean isInitialized() {
      return isInitialized;
    }
    */

}
