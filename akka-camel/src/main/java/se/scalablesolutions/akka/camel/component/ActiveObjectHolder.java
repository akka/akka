/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component;

import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.NoSuchBeanException;
import org.apache.camel.component.bean.BeanInfo;
import org.apache.camel.component.bean.RegistryBean;

/**
 * @author Martin Krasser
 */
public class ActiveObjectHolder extends RegistryBean {

    private Map<String, Object> activeObjectRegistry;
        
    public ActiveObjectHolder(Map<String, Object> activeObjectRegistry, CamelContext context, String name) {
        super(context, name);
        this.activeObjectRegistry = activeObjectRegistry;
    }

    @Override
    public BeanInfo getBeanInfo() {
        return new BeanInfo(getContext(), getBean().getClass(), getParameterMappingStrategy());
    }

    @Override
    public Object getBean() throws NoSuchBeanException {
        return activeObjectRegistry.get(getName());
    }
    
}
