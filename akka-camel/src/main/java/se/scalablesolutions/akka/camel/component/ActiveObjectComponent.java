/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.camel.Endpoint;
import org.apache.camel.Processor;
import org.apache.camel.component.bean.BeanComponent;
import org.apache.camel.component.bean.BeanEndpoint;
import org.apache.camel.component.bean.BeanHolder;

/**
 * Camel component for accessing active objects.
 *
 * @author Martin Krasser
 */
public class ActiveObjectComponent extends BeanComponent {

    public static final String DEFAULT_SCHEMA = "actobj";

    private Map<String, Object> registry = new ConcurrentHashMap<String, Object>();

    public Map<String, Object> getActiveObjectRegistry() {
        return registry;
    }

    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        BeanEndpoint beanEndpoint = new BeanEndpoint(uri, this);
        beanEndpoint.setBeanName(remaining);
        beanEndpoint.setBeanHolder(createBeanHolder(remaining));
        Processor processor = beanEndpoint.getProcessor();
        setProperties(processor, parameters);
        return beanEndpoint;
    }

    private BeanHolder createBeanHolder(String beanName) throws Exception {
        BeanHolder holder = new ActiveObjectHolder(registry, getCamelContext(), beanName).createCacheHolder();
        registry.remove(beanName);
        return holder;
    }

}
