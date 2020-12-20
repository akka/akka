/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sourceorflow;

import akka.japi.pf.PFBuilder;
import akka.stream.javadsl.Source;

import java.util.Arrays;

public class MapError {

  public static void main(String[] args) {

    // #map-error

    Source.from(Arrays.asList(-1, 0, 1))
        .map(x -> 1 / x)
        .mapError(
            new PFBuilder<Throwable, Throwable>()
                .match(
                    ArithmeticException.class,
                    (ArithmeticException e) ->
                        new UnsupportedOperationException(
                            "Divide by Zero Operation is not supported"))
                .build());
    // #map-error
  }
}
