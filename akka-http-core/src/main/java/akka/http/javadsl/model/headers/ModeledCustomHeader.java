/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Rendering;
import akka.util.Helpers;

/**
 * Support class for building user-defined custom headers defined by implementing `name` and `value`.
 * By implementing a {@link ModeledCustomHeader} along with {@link ModeledCustomHeaderFactory} instead of {@link CustomHeader} directly,
 * convenience methods that allow parsing this user-defined header from {@link akka.http.javadsl.model.HttpHeader}  are
 * available to use.
 */
public abstract class ModeledCustomHeader extends CustomHeader {

  private final String name;
  private final String value;

  protected ModeledCustomHeader(final String name, final String value) {
    super();
    this.name = name;
    this.value = value;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String lowercaseName() {
    return Helpers.toRootLowerCase(name);
  }

  @Override
  public String value() {
    return value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R extends Rendering> R render(R r) {
    return (R) r.$tilde$tilde(name).$tilde$tilde(':').$tilde$tilde(' ').$tilde$tilde(value);
  }
}
