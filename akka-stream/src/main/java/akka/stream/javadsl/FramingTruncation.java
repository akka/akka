/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

/** Determines mode in which [[Framing]] operates. */
public enum FramingTruncation {
  ALLOW,
  DISALLOW
}
