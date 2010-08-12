/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

/**
 * XML configuration tags.
 * @author michaelkober
 * @author Martin Krasser
 */
object AkkaSpringConfigurationTags {

  // --- TAGS
  //
  // top level tags
  val TYPED_ACTOR_TAG = "typed-actor"
  val SUPERVISION_TAG = "supervision"
  val DISPATCHER_TAG = "dispatcher"
  val PROPERTYENTRY_TAG = "property"
  val CAMEL_SERVICE_TAG = "camel-service"

  // typed-actor sub tags
  val REMOTE_TAG = "remote"

  // superivision sub tags
  val TYPED_ACTORS_TAG = "typed-actors"
  val STRATEGY_TAG = "restart-strategy"
  val TRAP_EXISTS_TAG = "trap-exits"
  val TRAP_EXIT_TAG = "trap-exit"

  // dispatcher sub tags
  val THREAD_POOL_TAG = "thread-pool"

  // camel-service sub tags
  val CAMEL_CONTEXT_TAG = "camel-context"

  // --- ATTRIBUTES
  //
  // typed actor attributes
  val TIMEOUT = "timeout"
  val IMPLEMENTATION = "implementation"
  val INTERFACE = "interface"
  val TRANSACTIONAL = "transactional"
  val HOST = "host"
  val PORT = "port"
  val LIFECYCLE = "lifecycle"
  val SCOPE = "scope"

  // supervision attributes
  val FAILOVER = "failover"
  val RETRIES = "retries"
  val TIME_RANGE = "timerange"

  // dispatcher attributes
  val NAME = "name"
  val REF = "ref"
  val TYPE = "type"
  val AGGREGATE = "aggregate"  // HawtDispatcher

  // thread pool attributes
  val QUEUE = "queue"
  val CAPACITY = "capacity"
  val FAIRNESS = "fairness"
  val CORE_POOL_SIZE = "core-pool-size"
  val MAX_POOL_SIZE = "max-pool-size"
  val KEEP_ALIVE = "keep-alive"
  val BOUND ="bound"
  val REJECTION_POLICY ="rejection-policy"

  // --- VALUES
  //
  // Lifecycle
  val VAL_LIFECYCYLE_TEMPORARY = "temporary"
  val VAL_LIFECYCYLE_PERMANENT = "permanent"

  val VAL_SCOPE_SINGLETON = "singleton"
  val VAL_SCOPE_PROTOTYPE = "prototype"

  // Failover
  val VAL_ALL_FOR_ONE = "AllForOne"
  val VAL_ONE_FOR_ONE = "OneForOne"

  // rejection policies
  val VAL_ABORT_POLICY = "abort-policy"
  val VAL_CALLER_RUNS_POLICY = "caller-runs-policy"
  val VAL_DISCARD_OLDEST_POLICY = "discard-oldest-policy"
  val VAL_DISCARD_POLICY = "discard-policy"

  // dispatcher queue types
  val VAL_BOUNDED_LINKED_BLOCKING_QUEUE = "bounded-linked-blocking-queue"
  val VAL_UNBOUNDED_LINKED_BLOCKING_QUEUE = "unbounded-linked-blocking-queue"
  val VAL_SYNCHRONOUS_QUEUE = "synchronous-queue"
  val VAL_BOUNDED_ARRAY_BLOCKING_QUEUE = "bounded-array-blocking-queue"

  // dispatcher types
  val EXECUTOR_BASED_EVENT_DRIVEN = "executor-based-event-driven"
  val EXECUTOR_BASED_EVENT_DRIVEN_WORK_STEALING = "executor-based-event-driven-work-stealing"
  val REACTOR_BASED_THREAD_POOL_EVENT_DRIVEN = "reactor-based-thread-pool-event-driven"
  val REACTOR_BASED_SINGLE_THREAD_EVENT_DRIVEN = "reactor-based-single-thread-event-driven"
  val THREAD_BASED = "thread-based"
  val HAWT = "hawt"

}
