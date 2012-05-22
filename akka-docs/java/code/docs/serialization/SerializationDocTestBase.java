/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.serialization;

import org.junit.Test;
import static org.junit.Assert.*;
//#imports
import akka.actor.*;
import akka.serialization.*;
import com.typesafe.config.*;

//#imports

public class SerializationDocTestBase {
  //#my-own-serializer
    public static class MyOwnSerializer extends JSerializer {

      // This is whether "fromBinary" requires a "clazz" or not
      @Override public boolean includeManifest() {
          return false;
      }

      // Pick a unique identifier for your Serializer,
      // you've got a couple of billions to choose from,
      // 0 - 16 is reserved by Akka itself
      @Override public int identifier() {
          return 1234567;
      }

      // "toBinary" serializes the given object to an Array of Bytes
      @Override public byte[] toBinary(Object obj) {
        // Put the code that serializes the object here
        //#...
        return new byte[0];
        //#...
      }

      // "fromBinary" deserializes the given array,
      // using the type hint (if any, see "includeManifest" above)
      @Override public Object fromBinaryJava(byte[] bytes,
                     Class<?> clazz) {
        // Put your code that deserializes here
        //#...
        return null;
        //#...
      }
    }
//#my-own-serializer

    @Test public void serializeActorRefs() {
        final ActorSystem theActorSystem =
                ActorSystem.create("whatever");
        final ActorRef theActorRef =
                theActorSystem.deadLetters(); // Of course this should be you

        //#actorref-serializer
        // Serialize
        // (beneath toBinary)
        final Address transportAddress =
                Serialization.currentTransportAddress().value();
        String identifier;

        // If there is no transportAddress,
        // it means that either this Serializer isn't called
        // within a piece of code that sets it,
        // so either you need to supply your own,
        // or simply use the local path.
        if (transportAddress == null) identifier = theActorRef.path().toString();
        else identifier = theActorRef.path().toStringWithAddress(transportAddress);
        // Then just serialize the identifier however you like


        // Deserialize
        // (beneath fromBinary)
        final ActorRef deserializedActorRef = theActorSystem.actorFor(identifier);
        // Then just use the ActorRef
        //#actorref-serializer
        theActorSystem.shutdown();
    }


    @Test public void demonstrateTheProgrammaticAPI() {
      //#programmatic
      ActorSystem system = ActorSystem.create("example");

      // Get the Serialization Extension
      Serialization serialization = SerializationExtension.get(system);

      // Have something to serialize
      String original = "woohoo";

      // Find the Serializer for it
      Serializer serializer = serialization.findSerializerFor(original);

      // Turn it into bytes
      byte[] bytes = serializer.toBinary(original);

      // Turn it back into an object,
      // the nulls are for the class manifest and for the classloader
      String back = (String)serializer.fromBinary(bytes);

      // Voilá!
      assertEquals(original, back);

      //#programmatic
      system.shutdown();
    }
}
