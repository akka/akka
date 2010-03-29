/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

/**
 * XML configuration tags.
 * @author michaelkober
 */
object AkkaSpringConfigurationTags {
  // top level tags
  val ACTIVE_OBJECT_TAG = "active-object"
  val SUPERVISION_TAG = "supervision"
  // active-object sub tags
  val RESTART_CALLBACKS_TAG = "restart-callbacks"
  val REMOTE_TAG = "remote";
  // superivision sub tags
  val ACTIVE_OBJECTS_TAG = "active-objects"
  val STRATEGY_TAG = "restart-strategy"
  val TRAP_EXISTS_TAG = "trap-exits"
  val TRAP_EXIT_TAG = "trap-exit"
  // active object attributes
  val TIMEOUT = "timeout"
  val TARGET = "target"
  val INTERFACE = "interface"
  val TRANSACTIONAL = "transactional"
  val HOST = "host"
  val PORT = "port"
  val PRE_RESTART = "pre"
  val POST_RESTART = "post"
  val LIFECYCLE = "lifecycle"
  // supervision attributes
  val FAILOVER = "failover"
  val RETRIES = "retries"
  val TIME_RANGE = "timerange"
  // Value types
  val VAL_LIFECYCYLE_TEMPORARY = "temporary"
  val VAL_LIFECYCYLE_PERMANENT = "permanent"
  val VAL_ALL_FOR_ONE = "AllForOne"
  val VAL_ONE_FOR_ONE = "OneForOne"
}