/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import java.util.Map

import org.apache.camel.{ProducerTemplate, CamelContext}
import org.apache.camel.impl.DefaultCamelContext

import se.scalablesolutions.akka.camel.component.TypedActorComponent
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.JavaAPI.{Option => JOption}

/**
 * Manages the lifecycle of a CamelContext. Allowed transitions are
 * init -> start -> stop -> init -> ... etc.
 *
 * @author Martin Krasser
 */
trait CamelContextLifecycle extends CamelContextLifecycleJavaAPI with Logging {
  // TODO: enforce correct state transitions
  // valid: init -> start -> stop -> init ...

  private var _context: Option[CamelContext] = None
  private var _template: Option[ProducerTemplate] = None

  private var _initialized = false
  private var _started = false

  /**
   * Camel component for accessing typed actors.
   */
  private[camel] var typedActorComponent: TypedActorComponent = _

  /**
   * Registry in which typed actors are TEMPORARILY registered during
   * creation of Camel routes to these actors.
   */
  private[camel] var typedActorRegistry: Map[String, AnyRef] = _

  /**
   * Returns <code>Some(CamelContext)</code> if <code>CamelContextLifecycle</code>
   * has been initialized, otherwise <code>None</code>.
   */
  protected def context: Option[CamelContext] = _context

  /**
   * Returns <code>Some(ProducerTemplate)</code> if <code>CamelContextLifecycle</code>
   * has been initialized, otherwise <code>None</code>.
   */
  protected def template: Option[ProducerTemplate] = _template

  def mandatoryContext =
    if (context.isDefined) context.get
    else throw new IllegalStateException("no current CamelContext")

  def mandatoryTemplate =
    if (template.isDefined) template.get
    else throw new IllegalStateException("no current ProducerTemplate")
  
  def initialized = _initialized
  def started = _started

  /**
   * Starts the CamelContext and an associated ProducerTemplate.
   */
  def start = {
    for {
      c <- context
      t <- template
    } {
      c.start
      t.start
      _started = true
      log.info("Camel context started")
    }
  }

  /**
   * Stops the CamelContext and the associated ProducerTemplate.
   */
  def stop = {
    for {
      t <- template
      c <- context
    } {
      t.stop
      c.stop
      _started = false
      log.info("Camel context stopped")
    }
  }

  /**
   * Initializes this lifecycle object with the a DefaultCamelContext.
   */
  def init(): Unit = init(new DefaultCamelContext)

  /**
   * Initializes this lifecycle object with the given CamelContext. For the passed
   * CamelContext, stream-caching is enabled. If applications want to disable stream-
   * caching they can do so after this method returned and prior to calling start.
   * This method also registers a new TypedActorComponent at the passes CamelContext 
   * under a name defined by TypedActorComponent.InternalSchema.
   */
  def init(context: CamelContext) {
    this.typedActorComponent = new TypedActorComponent
    this.typedActorRegistry = typedActorComponent.typedActorRegistry

    context.setStreamCaching(true)
    context.addComponent(TypedActorComponent.InternalSchema, typedActorComponent)

    this._context = Some(context)
    this._template = Some(context.createProducerTemplate)

    _initialized = true
    log.info("Camel context initialized")
  }
}

/**
 * Java API for CamelContextLifecycle.
 *
 * @author Martin Krasser
 */
trait CamelContextLifecycleJavaAPI { this: CamelContextLifecycle =>
  /**
   * Returns <code>Some(CamelContext)</code> if <code>CamelContextLifecycle</code>
   * has been initialized, otherwise <code>None</code>.
   */
  def getContext: JOption[CamelContext] = context

  /**
   * Returns <code>Some(ProducerTemplate)</code> if <code>CamelContextLifecycle</code>
   * has been initialized, otherwise <code>None</code>.
   */
  def getTemplate: JOption[ProducerTemplate] = template
}

/**
 * Manages a global CamelContext and an associated ProducerTemplate.
 */
object CamelContextManager extends CamelContextLifecycle with CamelContextLifecycleJavaAPI {
  /**
   * Returns <code>Some(CamelContext)</code> if <code>CamelContextLifecycle</code>
   * has been initialized, otherwise <code>None</code>.
   */
  override def context: Option[CamelContext] = super.context

  /**
   * Returns <code>Some(ProducerTemplate)</code> if <code>CamelContextLifecycle</code>
   * has been initialized, otherwise <code>None</code>.
   */
  override def template: Option[ProducerTemplate] = super.template
}
