/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor;
/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class AddressTest extends JUnitSuite {

  @Test
  public void portAddressAccessible() {
    Address address = new Address("akka", "MySystem", "localhost", 2525);
    assertEquals(Optional.of(2525), address.getPort());
    assertEquals(Optional.of("localhost"), address.getHost());
  }
}
