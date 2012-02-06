/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.serialization;

import akka.serialization.JSerializer;
import akka.serialization.Serialization;
import akka.serialization.SerializationExtension;
import akka.serialization.Serializer;
import org.junit.Test;
import static org.junit.Assert.*;
//#imports

import akka.serialization.*;
import akka.actor.ActorSystem;
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
      // into the optionally provided classLoader.
      @Override public Object fromBinary(byte[] bytes,
                     Class clazz,
                     ClassLoader classLoader) {
        // Put your code that deserializes here
        //#...
        return null;
        //#...
      }
    }
//#my-own-serializer
    @Test public void haveExamples() {
        /*
        //#serialize-messages-config
        akka {
          actor {
            serialize-messages = on
          }
        }
        //#serialize-messages-config

        //#serialize-creators-config
        akka {
          actor {
            serialize-creators = on
          }
        }
        //#serialize-creators-config


        //#serialize-serializers-config
        akka {
          actor {
            serializers {
              default = "akka.serialization.JavaSerializer"

              myown = "akka.docs.serialization.MyOwnSerializer"
            }
          }
        }
        //#serialize-serializers-config

        //#serialization-bindings-config
        akka {
          actor {
            serializers {
              default = "akka.serialization.JavaSerializer"
              java = "akka.serialization.JavaSerializer"
              proto = "akka.serialization.ProtobufSerializer"
              myown = "akka.docs.serialization.MyOwnSerializer"
            }

            serialization-bindings {
             java = ["java.lang.String",
                     "app.my.Customer"]
             proto = ["com.google.protobuf.Message"]
             myown = ["my.own.BusinessObject",
                      "something.equally.Awesome",
                      "akka.docs.serialization.MyOwnSerializable"
                      "java.lang.Boolean"]
           }
          }
        }
        //#serialization-bindings-config
        */
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
