/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.ddata.japi.protobuf;

//#serializer
import docs.ddata.japi.TwoPhaseSet;
import docs.ddata.protobuf.msg.TwoPhaseSetMessages;
import docs.ddata.protobuf.msg.TwoPhaseSetMessages.TwoPhaseSet.Builder;
import java.util.ArrayList;
import java.util.Collections;

import akka.actor.ExtendedActorSystem;
import akka.cluster.ddata.GSet;
import akka.cluster.ddata.protobuf.AbstractSerializationSupport;

public class TwoPhaseSetSerializer extends AbstractSerializationSupport {
  
  private final ExtendedActorSystem system;

  public TwoPhaseSetSerializer(ExtendedActorSystem system) {
    this.system = system;
  }
  
  @Override
  public ExtendedActorSystem system() {
    return this.system;
  }

  @Override
  public boolean includeManifest() {
    return false;
  }

  @Override 
  public int identifier() {
    return 99998;
  }

  @Override
  public byte[] toBinary(Object obj) {
    if (obj instanceof TwoPhaseSet) {
      return twoPhaseSetToProto((TwoPhaseSet) obj).toByteArray();
    } else {
      throw new IllegalArgumentException(
          "Can't serialize object of type " + obj.getClass());
    }
  }

  @Override
  public Object fromBinaryJava(byte[] bytes, Class<?> manifest) {
    return twoPhaseSetFromBinary(bytes);
  }

  protected TwoPhaseSetMessages.TwoPhaseSet twoPhaseSetToProto(TwoPhaseSet twoPhaseSet) {
    Builder b = TwoPhaseSetMessages.TwoPhaseSet.newBuilder();
    ArrayList<String> adds = new ArrayList<>(twoPhaseSet.adds.getElements());
    if (!adds.isEmpty()) {
      Collections.sort(adds);
      b.addAllAdds(adds);
    }
    ArrayList<String> removals = new ArrayList<>(twoPhaseSet.removals.getElements());
    if (!removals.isEmpty()) {
      Collections.sort(removals);
      b.addAllRemovals(removals);
    }
    return b.build();
  }

  protected TwoPhaseSet twoPhaseSetFromBinary(byte[] bytes) {
    try {  
      TwoPhaseSetMessages.TwoPhaseSet msg = 
          TwoPhaseSetMessages.TwoPhaseSet.parseFrom(bytes);
      GSet<String> adds = GSet.create();
      for (String elem : msg.getAddsList()) {
        adds = adds.add(elem);
      }
      GSet<String> removals = GSet.create();
      for (String elem : msg.getRemovalsList()) {
        removals = removals.add(elem);
      }
      return new TwoPhaseSet(adds, removals);
    } catch (Exception e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }
}
//#serializer


