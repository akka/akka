/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import java.util.Map

import org.apache.camel.{ProducerTemplate, CamelContext}
import org.apache.camel.impl.DefaultCamelContext

import se.scalablesolutions.akka.camel.component.ActiveObjectComponent
import se.scalablesolutions.akka.util.Logging

/**
 * Defines the lifecycle of a CamelContext. Allowed state transitions are
 * init -> start -> stop -> init -> ... etc.
 *
 * @author Martin Krasser
 */
trait CamelContextLifecycle extends Logging {
  // TODO: enforce correct state transitions
  // valid: init -> start -> stop -> init ...

  private var _context: CamelContext = _
  private var _template: ProducerTemplate = _

  private var _initialized = false
  private var _started = false

  /**
   * Camel component for accessing active objects.
   */
  private[camel] var activeObjectComponent: ActiveObjectComponent = _

  /**
   * Registry in which active objects are TEMPORARILY registered during
   * creation of Camel routes to active objects.
   */
  private[camel] var activeObjectRegistry: Map[String, AnyRef] = _

  /**
   *  Returns the managed CamelContext.
   */
  protected def context: CamelContext = _context

  /**
   * Returns the managed ProducerTemplate.
   */
  protected def template: ProducerTemplate = _template

  /**
   * Sets the managed CamelContext.
   */
  protected def context_= (context: CamelContext) { _context = context }

  /**
   * Sets the managed ProducerTemplate.
   */
  protected def template_= (template: ProducerTemplate) { _template = template }

  def initialized = _initialized
  def started = _started

  /**
   * Starts the CamelContext and ProducerTemplate.
   */
  def start = {
    context.start
    template.start
    _started = true
    log.info("Camel context started")
  }

  /**
   * Stops the CamelContext and ProducerTemplate.
   */
  def stop = {
    template.stop
    context.stop
    _initialized = false
    _started = false
    log.info("Camel context stopped")
  }

  /**
   * Initializes this lifecycle object with the a DefaultCamelContext.
   */
  def init(): Unit = init(new DefaultCamelContext)

  /**
   * Initializes this lifecycle object with the given CamelContext. For the passed
   * CamelContext stream-caching is enabled. If applications want to disable stream-
   * caching they can do so after this method returned and prior to calling start.
   * This method also registers a new
   * {@link se.scalablesolutions.akka.camel.component.ActiveObjectComponent} at
   * <code>context</code> under a name defined by ActiveObjectComponent.InternalSchema.
   */
  def init(context: CamelContext) {
    this.activeObjectComponent = new ActiveObjectComponent
    this.activeObjectRegistry = activeObjectComponent.activeObjectRegistry
    this.context = context
    this.context.setStreamCaching(true)
    this.context.addComponent(ActiveObjectComponent.InternalSchema, activeObjectComponent)
    this.template = context.createProducerTemplate
    _initialized = true
    log.info("Camel context initialized")
  }
}

/**
 * Makes a global CamelContext and ProducerTemplate accessible to applications. The lifecycle
 * of these objects is managed by se.scalablesolutions.akka.camel.CamelService.
 */
object CamelContextManager extends CamelContextLifecycle {
  override def context: CamelContext = super.context
  override def template: ProducerTemplate = super.template
}
