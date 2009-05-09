/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config;

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
public class DependencyBinding {
  private final Class intf;
  private final Class target;
  
  public DependencyBinding(final Class intf, final Class target) {
    this.intf = intf;
    this.target = target;
  }
  public Class getInterface() {
    return intf;
  }
  public Class getTarget() {
    return target;
  }
}
