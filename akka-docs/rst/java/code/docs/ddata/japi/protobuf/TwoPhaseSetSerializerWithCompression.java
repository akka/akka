/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.ddata.japi.protobuf;

import docs.ddata.japi.TwoPhaseSet;

import akka.actor.ExtendedActorSystem;

public class TwoPhaseSetSerializerWithCompression extends TwoPhaseSetSerializer {
  public TwoPhaseSetSerializerWithCompression(ExtendedActorSystem system) {
    super(system);
  }
  
  //#compression
  @Override
  public byte[] toBinary(Object obj) {
    if (obj instanceof TwoPhaseSet) {
      return compress(twoPhaseSetToProto((TwoPhaseSet) obj));
    } else {
      throw new IllegalArgumentException(
          "Can't serialize object of type " + obj.getClass());
    }
  }

  @Override
  public Object fromBinaryJava(byte[] bytes, Class<?> manifest) {
    return twoPhaseSetFromBinary(decompress(bytes));
  }
  //#compression
}

