/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.model.headers;

import akka.http.impl.util.EnhancedString;
import akka.http.impl.util.Rendering;

import java.util.Locale;

public abstract class ModeledCustomHeader extends CustomHeader {

  @Override
  public boolean renderInRequests() {
    return true;
  }

  @Override
  public boolean renderInResponses() {
    return true;
  }

  @Override
  @SuppressWarnings("unchecked")
  final public <R extends Rendering> R render(R r) {
    return (R) r.$tilde$tilde(name()).$tilde$tilde(':').$tilde$tilde(' ').$tilde$tilde(value());
  }

  @Override
  public String lowercaseName() {
    return new EnhancedString(name()).toRootLowerCase();
  }
}
