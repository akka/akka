/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import junit.framework.TestCase;
import se.scalablesolutions.akka.serialization.SerializerFactory;

public class ProtobufSerializationTest extends TestCase {
    public void testOutIn() throws Exception {
      SerializerFactory factory = new SerializerFactory();
      ProtobufProtocol.ProtobufPOJO pojo1 = ProtobufProtocol.ProtobufPOJO.getDefaultInstance().toBuilder().setId(1).setName("protobuf").setStatus(true).build();

      byte[] bytes = factory.getProtobuf().out(pojo1);
      Object obj = factory.getProtobuf().in(bytes, pojo1.getClass());

      assertTrue(obj instanceof ProtobufProtocol.ProtobufPOJO);
      ProtobufProtocol.ProtobufPOJO pojo2 = (ProtobufProtocol.ProtobufPOJO)obj;
      assertEquals(pojo1.getId(), pojo2.getId());
      assertEquals(pojo1.getName(), pojo2.getName());
      assertEquals(pojo1.getStatus(), pojo2.getStatus());
    }
    
    public void testDeepClone() throws Exception {
      SerializerFactory factory = new SerializerFactory();
      ProtobufProtocol.ProtobufPOJO pojo1 = ProtobufProtocol.ProtobufPOJO.getDefaultInstance().toBuilder().setId(1).setName("protobuf").setStatus(true).build();

      Object obj = factory.getProtobuf().deepClone(pojo1);

      assertTrue(obj instanceof ProtobufProtocol.ProtobufPOJO);
      ProtobufProtocol.ProtobufPOJO pojo2 = (ProtobufProtocol.ProtobufPOJO)obj;
      assertEquals(pojo1.getId(), pojo2.getId());
      assertEquals(pojo1.getName(), pojo2.getName());
      assertEquals(pojo1.getStatus(), pojo2.getStatus());
    }
}

