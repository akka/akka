/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

/** Marker-interface for entity types that can be used in any context */
public interface UniversalEntity extends RequestEntity, ResponseEntity, BodyPartEntity {}
