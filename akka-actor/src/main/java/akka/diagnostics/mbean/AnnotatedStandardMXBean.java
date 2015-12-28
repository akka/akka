/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package akka.diagnostics.mbean;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * INTERNAL API
 *
 * Copied from http://actimem.com/java/jmx-annotations/ which source code is
 * licensed under the Apache License, Version 2.0.
 *
 * The <code>StandardMBean</code> falls short with providing meaningful
 * descriptions and parameter names. This class reads those things from
 * {@link Description} and {@link Name} annotations of the <code>MBean</code>
 * interface class and methods.
 *
 * Usage:
 * <code>mbeanServer.registerMBean(new AnnotatedStandardMXBean(mbeanImpl, SomeMBean.class), name);</code>
 */
public class AnnotatedStandardMXBean extends StandardMBean {

  /**
   * @param implementation
   *          the <code>MBean</code> implementation
   * @param mbeanInterface
   *          the <code>MBean</code> interface that is annotated with
   *          <code>Description</code> and <code>Name</code>.
   */
  public <T> AnnotatedStandardMXBean(T implementation, Class<T> mbeanInterface) throws NotCompliantMBeanException {
    super(implementation, mbeanInterface, true);
  }

  @Override
  protected String getDescription(MBeanInfo beanInfo) {
    Description annotation = getMBeanInterface().getAnnotation(Description.class);
    if (annotation != null) {
      return annotation.value();
    }
    return beanInfo.getDescription();
  }

  /**
   * Returns the description of an Attribute
   */
  @Override
  protected String getDescription(MBeanAttributeInfo info) {
    String attributeName = info.getName();
    String getterName = String.format("get%s%s", attributeName.substring(0, 1).toUpperCase(),
        attributeName.substring(1));
    Method m = methodByName(getMBeanInterface(), getterName, new String[] {});
    if (m != null) {
      Description d = m.getAnnotation(Description.class);
      if (d != null)
        return d.value();
    }
    return info.getDescription();
  }

  /**
   * Returns the description of an operation
   */
  @Override
  protected String getDescription(MBeanOperationInfo op) {
    Method m = methodByOperation(getMBeanInterface(), op);
    if (m != null) {
      Description d = m.getAnnotation(Description.class);
      if (d != null)
        return d.value();
    }
    return op.getDescription();
  }

  /**
   * Returns the description of a parameter
   */
  @Override
  protected String getDescription(MBeanOperationInfo op, MBeanParameterInfo param, int paramNo) {
    Method m = methodByOperation(getMBeanInterface(), op);
    if (m != null) {
      Description pname = getParameterAnnotation(m, paramNo, Description.class);
      if (pname != null)
        return pname.value();
    }
    return getParameterName(op, param, paramNo);
  }

  /**
   * Returns the name of a parameter
   */
  @Override
  protected String getParameterName(MBeanOperationInfo op, MBeanParameterInfo param, int paramNo) {
    Method m = methodByOperation(getMBeanInterface(), op);
    if (m != null) {
      Name pname = getParameterAnnotation(m, paramNo, Name.class);
      if (pname != null)
        return pname.value();
    }
    return param.getName();
  }

  /**
   * Returns the annotation of the given class for the method.
   */
  private static <A extends Annotation> A getParameterAnnotation(Method m, int paramNo, Class<A> annotation) {
    for (Annotation a : m.getParameterAnnotations()[paramNo]) {
      if (annotation.isInstance(a))
        return annotation.cast(a);
    }
    return null;
  }

  /**
   * Finds a method within the interface using the method's name and parameters
   */
  private static Method methodByName(Class<?> mbeanInterface, String name, String... paramTypes) {
    try {
      final ClassLoader loader = mbeanInterface.getClassLoader();
      final Class<?>[] paramClasses = new Class<?>[paramTypes.length];
      for (int i = 0; i < paramTypes.length; i++)
        paramClasses[i] = classForName(paramTypes[i], loader);
      return mbeanInterface.getMethod(name, paramClasses);
    } catch (RuntimeException e) {
      // avoid accidentally catching unexpected runtime exceptions
      throw e;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Returns the method from the interface for the given bean operation info.
   */
  private static Method methodByOperation(Class<?> mbeanInterface, MBeanOperationInfo op) {
    final MBeanParameterInfo[] params = op.getSignature();
    final String[] paramTypes = new String[params.length];
    for (int i = 0; i < params.length; i++)
      paramTypes[i] = params[i].getType();

    return methodByName(mbeanInterface, op.getName(), paramTypes);
  }

  /**
   * Finds the class given its name. <br>
   * This method also retrieves primitive types (unlike
   * {@code Class#forName(String)}).
   */
  private static Class<?> classForName(String name, ClassLoader loader) throws ClassNotFoundException {
    Class<?> c = primitiveClasses.get(name);
    if (c == null)
      c = Class.forName(name, false, loader);
    return c;
  }

  private static final Map<String, Class<?>> primitiveClasses = new HashMap<String, Class<?>>();
  static {
    Class<?>[] primitives = { byte.class, short.class, int.class, long.class, float.class, double.class, char.class,
        boolean.class };
    for (Class<?> clazz : primitives)
      primitiveClasses.put(clazz.getName(), clazz);
  }

}