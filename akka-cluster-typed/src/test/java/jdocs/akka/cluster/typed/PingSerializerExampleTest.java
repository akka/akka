/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.typed;

import akka.actor.ExtendedActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import akka.actor.typed.javadsl.Adapter;
import akka.serialization.SerializerWithStringManifest;

import java.io.NotSerializableException;
import java.nio.charset.StandardCharsets;

public class PingSerializerExampleTest {

  public class Pong {}

  public class Ping {
    public final akka.actor.typed.ActorRef<Pong> replyTo;

    public Ping(ActorRef<Pong> replyTo) {
      this.replyTo = replyTo;
    }
  }

  //#serializer
  public class PingSerializer extends SerializerWithStringManifest {

    ExtendedActorSystem system;
    ActorRefResolver actorRefResolver;

    String PingManifest = "a";
    String PongManifest = "b";

    PingSerializer(ExtendedActorSystem system) {
      this.system = system;
      actorRefResolver = ActorRefResolver.get(Adapter.toTyped(system));
    }

    @Override
    public int identifier() {
      return 97876;
    }

    @Override
    public String manifest(Object obj) {
      if (obj instanceof Ping)
        return PingManifest;
      else if (obj instanceof Pong)
        return PongManifest;
      else
        throw new IllegalArgumentException("Unknown type: " + obj);
    }

    @Override
    public byte[] toBinary(Object obj) {
      if (obj instanceof Ping)
        return actorRefResolver.toSerializationFormat(((Ping) obj).replyTo).getBytes(StandardCharsets.UTF_8);
      else if (obj instanceof Pong)
        return new byte[0];
      else
        throw new IllegalArgumentException("Unknown type: " + obj);
    }

    @Override
    public Object fromBinary(byte[] bytes, String manifest) throws NotSerializableException {
      if (PingManifest.equals(manifest)) {
        String str = new String(bytes, StandardCharsets.UTF_8);
        ActorRef<Pong> ref = actorRefResolver.resolveActorRef(str);
        return new Ping(ref);
      } else if (PongManifest.equals(manifest)) {
        return new Pong();
      } else {
        throw new NotSerializableException("Unable to handle manifest: " + manifest);
      }
    }
  }
  //#serializer
}
