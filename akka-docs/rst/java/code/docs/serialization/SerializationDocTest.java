/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.serialization;

import java.io.UnsupportedEncodingException;

import akka.testkit.JavaTestKit;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

//#imports
import akka.actor.*;
import akka.serialization.*;

//#imports

public class SerializationDocTest {
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

  static class Customer {
    public final String name;
    
    Customer(String name) {
      this.name = name;
    }
  }
  
  static class User {
    public final String name;
    
    User(String name) {
      this.name = name;
    }
  }
  
  static
  //#my-own-serializer2
  public class MyOwnSerializer2 extends SerializerWithStringManifest {

    private static final String CUSTOMER_MANIFEST = "customer";
    private static final String USER_MANIFEST = "user";
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    
    // Pick a unique identifier for your Serializer,
    // you've got a couple of billions to choose from,
    // 0 - 16 is reserved by Akka itself
    @Override public int identifier() {
      return 1234567;
    }
    
    @Override public String manifest(Object obj) {
      if (obj instanceof Customer)
        return CUSTOMER_MANIFEST;
      else if (obj instanceof User)
        return USER_MANIFEST;
      else 
        throw new IllegalArgumentException("Unknown type: " + obj);
    }

    // "toBinary" serializes the given object to an Array of Bytes
    @Override public byte[] toBinary(Object obj) {
      // Put the real code that serializes the object here
      try {
        if (obj instanceof Customer)
          return ((Customer) obj).name.getBytes(UTF_8);
        else if (obj instanceof User)
          return ((User) obj).name.getBytes(UTF_8);
        else 
          throw new IllegalArgumentException("Unknown type: " + obj);
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }

    // "fromBinary" deserializes the given array,
    // using the type hint
    @Override public Object fromBinary(byte[] bytes, String manifest) {
      // Put the real code that deserializes here
      try {
        if (manifest.equals(CUSTOMER_MANIFEST))
          return new Customer(new String(bytes, UTF_8));
        else if (manifest.equals(USER_MANIFEST))
          return new User(new String(bytes, UTF_8));
        else 
          throw new IllegalArgumentException("Unknown manifest: " + manifest);
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }
  }
//#my-own-serializer2

  @Test public void serializeActorRefs() {
    final ExtendedActorSystem extendedSystem = (ExtendedActorSystem)
      ActorSystem.create("whatever");
    final ActorRef theActorRef =
      extendedSystem.deadLetters(); // Of course this should be you

    //#actorref-serializer
    // Serialize
    // (beneath toBinary)
    String identifier = Serialization.serializedActorPath(theActorRef);

    // Then just serialize the identifier however you like

    // Deserialize
    // (beneath fromBinary)
    final ActorRef deserializedActorRef = extendedSystem.provider().resolveActorRef(
      identifier);
    // Then just use the ActorRef
    //#actorref-serializer
    JavaTestKit.shutdownActorSystem(extendedSystem);
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
  static
  //#external-address
  public class ExternalAddressExample {
    //#external-address
    final ActorSystem system = ActorSystem.create();
    //#external-address
    public String serializeTo(ActorRef ref, Address remote) {
      return ref.path().toSerializationFormatWithAddress(
          ExternalAddress.ID.get(system).getAddressFor(remote));
    }
  }

  //#external-address

  static
  //#external-address-default
  public class DefaultAddressExt implements Extension {
    private final ExtendedActorSystem system;

    public DefaultAddressExt(ExtendedActorSystem system) {
      this.system = system;
    }

    public Address getAddress() {
      return system.provider().getDefaultAddress();
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
    JavaTestKit.shutdownActorSystem(system);
  }
}
