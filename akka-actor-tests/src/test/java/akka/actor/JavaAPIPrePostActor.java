package akka.actor;

import akka.japi.PartialProcedure;
import akka.japi.Option;

public class JavaAPIPrePostActor extends UntypedActor {
  @Override
  public void onReceive(Object message) {
    // this is not called since we override onReceivePartial instead
  }

  @Override
  public PartialProcedure<Object> onReceivePartial() {
    PartialProcedure<Object> handler = new PartialProcedure<Object>() {
      public void apply(Object o) {
        getSender().tell("onReceivePartial");
      }
      public boolean isDefinedAt(Object o) {
        return (o instanceof String && ((String) o).equals("onReceivePartial"));
      }
    };
    return handler;
  }

  @Override
  public Option<PartialProcedure<Object>> onPreReceive() {
    PartialProcedure<Object> handler = new PartialProcedure<Object>() {
      public void apply(Object o) {
        getSender().tell("onPreReceive");
      }
      public boolean isDefinedAt(Object o) {
        return (o instanceof String && ((String) o).equals("onPreReceive"));
      }
    };
    return Option.some(handler);
  }

  @Override
    public Option<PartialProcedure<Object>> onPostReceive() {
    PartialProcedure<Object> handler = new PartialProcedure<Object>() {
      public void apply(Object o) {
        getSender().tell("onPostReceive");
      }
      public boolean isDefinedAt(Object o) {
        return (o instanceof String && ((String) o).equals("onPostReceive"));
      }
    };
    return Option.some(handler);
  }
}
