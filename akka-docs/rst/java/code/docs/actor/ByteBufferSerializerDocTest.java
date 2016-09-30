/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actor;

//#bytebufserializer-with-manifest
import akka.serialization.ByteBufferSerializer;
import akka.serialization.SerializerWithStringManifest;

//#bytebufserializer-with-manifest
import java.nio.ByteBuffer;

public class ByteBufferSerializerDocTest {


  static //#bytebufserializer-with-manifest
  class ExampleByteBufSerializer extends SerializerWithStringManifest
    implements ByteBufferSerializer {

    @Override
    public int identifier() {
      return 1337;
    }

    @Override
    public String manifest(Object o) {
      return "serialized-" + o.getClass().getSimpleName();
    }

    @Override
    public byte[] toBinary(Object o) {
      final ByteBuffer buf = ByteBuffer.allocate(256); // in production code, aquire this from a BufferPool

      toBinary(o, buf);
      buf.flip();
      final byte[] bytes = new byte[buf.remaining()];
      buf.get(bytes);
      return bytes;
    }

    @Override
    public Object fromBinary(byte[] bytes, String manifest) {
      return fromBinary(ByteBuffer.wrap(bytes), manifest);
    }

    @Override
    public void toBinary(Object o, ByteBuffer buf) {
      // Implement actual serialization here
    }

    @Override
    public Object fromBinary(ByteBuffer buf, String manifest) {
      // Implement actual deserialization here
      return null;
    }
  }
  //#bytebufserializer-with-manifest

}
