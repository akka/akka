/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.serialization;

import org.junit.Test;
import static org.junit.Assert.*;
//#imports
import akka.actor.*;
import akka.remote.RemoteActorRefProvider;
import akka.serialization.*;

//#imports

public class SerializationDocTestBase {
  static
  //#my-own-serializer
  public class MyOwnSerializer extends JSerializer {

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

  static
  //#external-address
  public class ExternalAddressExt implements Extension {
    private final ExtendedActorSystem system;

    public ExternalAddressExt(ExtendedActorSystem system) {
      this.system = system;
    }

    public Address getAddressFor(Address remoteAddress) {
      final scala.Option<Address> optAddr = system.provider()
        .getExternalAddressFor(remoteAddress);
      if (optAddr.isDefined()) {
        return optAddr.get();
      } else {
        throw new UnsupportedOperationException(
          "cannot send to remote address " + remoteAddress);
      }
    }
  }

  //#external-address
  static
  //#external-address
  public class ExternalAddress extends
    AbstractExtensionId<ExternalAddressExt> implements ExtensionIdProvider {
    public static final ExternalAddress ID = new ExternalAddress();

    public ExternalAddress lookup() {
      return ID;
    }

    public ExternalAddressExt createExtension(ExtendedActorSystem system) {
      return new ExternalAddressExt(system);
    }
  }

  //#external-address

  public void demonstrateExternalAddress() {
    // this is not meant to be run, only to be compiled
    final ActorSystem system = ActorSystem.create();
    final Address remoteAddr = new Address("", "");
    // #external-address
    final Address addr = ExternalAddress.ID.get(system).getAddressFor(remoteAddr);
    // #external-address
  }

  static
  //#external-address-default
  public class DefaultAddressExt implements Extension {
    private final ExtendedActorSystem system;

    public DefaultAddressExt(ExtendedActorSystem system) {
      this.system = system;
    }

    public Address getAddress() {
      final ActorRefProvider provider = system.provider();
      if (provider instanceof RemoteActorRefProvider) {
        return ((RemoteActorRefProvider) provider).transport().addresses().head();
      } else {
        throw new UnsupportedOperationException("need RemoteActorRefProvider");
      }
    }
  }

  //#external-address-default
  static
  //#external-address-default
  public class DefaultAddress extends
      AbstractExtensionId<DefaultAddressExt> implements ExtensionIdProvider {
    public static final DefaultAddress ID = new DefaultAddress();

    public DefaultAddress lookup() {
      return ID;
    }

    public DefaultAddressExt createExtension(ExtendedActorSystem system) {
      return new DefaultAddressExt(system);
    }
  }
  
  //#external-address-default

  public void demonstrateDefaultAddress() {
    // this is not meant to be run, only to be compiled
    final ActorSystem system = ActorSystem.create();
    final Address remoteAddr = new Address("", "");
    // #external-address-default
    final Address addr = DefaultAddress.ID.get(system).getAddress();
    // #external-address-default
  }

  @Test
  public void demonstrateTheProgrammaticAPI() {
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
    String back = (String) serializer.fromBinary(bytes);

    // Voilá!
    assertEquals(original, back);

    //#programmatic
    system.shutdown();
  }
}
