/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import org.junit.*;
import static org.junit.Assert.*;

import junit.framework.TestSuite;
import se.scalablesolutions.akka.kernel.nio.ProxyServer;

public class NioTest extends TestSuite {

  @BeforeClass
  public static void initialize() {
  }

  @AfterClass
  public static void cleanup() {
  }

  @Test
  public void simpleRequestReply() {
    ProxyServer server = new ProxyServer();
    server.start();

    
  }

}