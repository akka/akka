/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.HttpHeader;
import akka.util.Helpers;

import java.util.Optional;

/**
 * Companion class for the {@link ModeledCustomHeader} class. It offers methods to create {@link ModeledCustomHeader}
 * from {@link String} or {@link HttpHeader}.
 */
public abstract class ModeledCustomHeaderFactory<H extends ModeledCustomHeader> {

  public abstract String name();

  public String lowercaseName() {
    return Helpers.toRootLowerCase(name());
  }

  /**
   * Parses the value checking that the format is correct.
   * It may throw if value is not correct
   */
  protected abstract H parse(final String value);

  /**
   * Creates a new {@code ModeledCustomHeader} from the value checking that the format is correct.
   * It may throw if value is not correct
   */
  public H create(final String value) {
    return parse(value);
  }

  /**
   * Transforms an {@code HttpHeader} to this {@code ModeledCustomHeader} if the name and value are correct.
   * It may throw in case of malformed headers 
   */
  public Optional<H> from(final HttpHeader header) {
    if (header.lowercaseName().equals(lowercaseName())) {
      return Optional.of(parse(header.value()));
    }
    return Optional.empty();
  }
}
