/**
 *  Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.{ ProducerTemplate, CamelContext }
import org.apache.camel.impl.DefaultCamelContext

import akka.event.EventHandler
import akka.japi.{ Option ⇒ JOption }

import TypedCamelAccess._

/**
 * Manages the lifecycle of a CamelContext. Allowed transitions are
 * init -> start -> stop -> init -> ... etc.
 *
 * @author Martin Krasser
 */
trait CamelContextLifecycle {
  // TODO: enforce correct state transitions
  // valid: init -> start -> stop -> init ...

  private var _context: Option[CamelContext] = None
  private var _template: Option[ProducerTemplate] = None

  private var _initialized = false
  private var _started = false

  /**
   * Returns <code>Some(CamelContext)</code> (containing the current CamelContext)
   * if <code>CamelContextLifecycle</code> has been initialized, otherwise <code>None</code>.
   */
  def context: Option[CamelContext] = _context

  /**
   * Returns <code>Some(ProducerTemplate)</code> (containing the current ProducerTemplate)
   * if <code>CamelContextLifecycle</code> has been initialized, otherwise <code>None</code>.
   */
  def template: Option[ProducerTemplate] = _template

  /**
   * Returns <code>Some(CamelContext)</code> (containing the current CamelContext)
   * if <code>CamelContextLifecycle</code> has been initialized, otherwise <code>None</code>.
   * <p>
   * Java API.
   */
  def getContext: JOption[CamelContext] = context

  /**
   * Returns <code>Some(ProducerTemplate)</code> (containing the current ProducerTemplate)
   * if <code>CamelContextLifecycle</code> has been initialized, otherwise <code>None</code>.
   * <p>
   * Java API.
   */
  def getTemplate: JOption[ProducerTemplate] = template

  /**
   * Returns the current <code>CamelContext</code> if this <code>CamelContextLifecycle</code>
   * has been initialized, otherwise throws an <code>IllegalStateException</code>.
   */
  def mandatoryContext =
    if (context.isDefined) context.get
    else throw new IllegalStateException("no current CamelContext")

  /**
   * Returns the current <code>ProducerTemplate</code> if this <code>CamelContextLifecycle</code>
   * has been initialized, otherwise throws an <code>IllegalStateException</code>.
   */
  def mandatoryTemplate =
    if (template.isDefined) template.get
    else throw new IllegalStateException("no current ProducerTemplate")

  /**
   * Returns the current <code>CamelContext</code> if this <code>CamelContextLifecycle</code>
   * has been initialized, otherwise throws an <code>IllegalStateException</code>.
   * <p>
   * Java API.
   */
  def getMandatoryContext = mandatoryContext

  /**
   * Returns the current <code>ProducerTemplate</code> if this <code>CamelContextLifecycle</code>
   * has been initialized, otherwise throws an <code>IllegalStateException</code>.
   * <p>
   * Java API.
   */
  def getMandatoryTemplate = mandatoryTemplate

  def initialized = _initialized
  def started = _started

  /**
   * Starts the CamelContext and an associated ProducerTemplate.
   */
  def start = {
    for {
      c ← context
      t ← template
    } {
      c.start()
      t.start()
      _started = true
      EventHandler.info(this, "Camel context started")
    }
  }

  /**
   * Stops the CamelContext and the associated ProducerTemplate.
   */
  def stop = {
    for {
      t ← template
      c ← context
    } {
      t.stop
      c.stop
      _started = false
      _initialized = false
      EventHandler.info(this, "Camel context stopped")
    }
  }

  /**
   * Initializes this instance a new DefaultCamelContext.
   */
  def init(): Unit = init(new DefaultCamelContext)

  /**
   * Initializes this instance with the given CamelContext. For the passed <code>context</code>
   * stream-caching is enabled. If applications want to disable stream-caching they can do so
   * after this method returned and prior to calling start.
   */
  def init(context: CamelContext) {
    context.setStreamCaching(true)

    for (tc ← TypedCamelModule.typedCamelObject) tc.onCamelContextInit(context)

    this._context = Some(context)
    this._template = Some(context.createProducerTemplate)

    _initialized = true
    EventHandler.info(this, "Camel context initialized")
  }
}

/**
 * Manages a global CamelContext and an associated ProducerTemplate.
 */
object CamelContextManager extends CamelContextLifecycle {

  // -----------------------------------------------------
  //  The inherited getters aren't statically accessible
  //  from Java. Therefore, they are redefined here.
  //  TODO: investigate if this is a Scala bug.
  // -----------------------------------------------------

  /**
   * see CamelContextLifecycle.getContext
   * <p>
   * Java API.
   */
  override def getContext: JOption[CamelContext] = super.getContext

  /**
   * see CamelContextLifecycle.getTemplate
   * <p>
   * Java API.
   */
  override def getTemplate: JOption[ProducerTemplate] = super.getTemplate

  /**
   * see CamelContextLifecycle.getMandatoryContext
   * <p>
   * Java API.
   */
  override def getMandatoryContext = super.getMandatoryContext

  /**
   * see CamelContextLifecycle.getMandatoryTemplate
   * <p>
   * Java API.
   */
  override def getMandatoryTemplate = super.getMandatoryTemplate
}
