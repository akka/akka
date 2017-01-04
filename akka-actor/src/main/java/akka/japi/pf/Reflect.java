/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.japi.pf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

import sun.reflect.ConstantPool;

/**
 * INTERNAL API
 */
public class Reflect {
    private static final Method getConstantPoolMethod;

    static {
        try {
            getConstantPoolMethod = Class.class.getDeclaredMethod("getConstantPool");
            getConstantPoolMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Couldn't find the 'getConstantPool' method - are you using the SUN JVM?",
                                       e);
        }
    }

    /**
     * Resolve the generic argument types of a lamb instance (even though types are not reified in Java)<br/>
     * Example: If a class/method is handed a Function&lt;Foo, Bar> then resolveGenericLambdaArgumentType for argument 0 (the only argument) will return String.class:
     * <pre>
     * Function&lt;String, Boolean> myStrToBoolFunc = strArg -> true;
     * assertThat(Reflect.resolveGenericLambdaArgumentType(myStrToBoolFunc, 0), equalTo(String.class));
     * </pre>
     * Example of a lambda with multiple arguments:
     * <pre>
     * BiConsumer&lt;Integer, Long> biConsumer = (i, l) -> {};
     * assertThat(Reflect.resolveGenericLambdaArgumentType(biConsumer, 0), equalTo(Integer.class));
     * assertThat(Reflect.resolveGenericLambdaArgumentType(biConsumer, 1), equalTo(Long.class));
     * </pre>
     */
    public static Class<?> resolveLambdaArgumentType(Object object, int argumentIndex) {
        Objects.requireNonNull(object,
                               "object");
        if (argumentIndex < 0) {
            throw new IllegalArgumentException("ArgumentIndex must be >= 0");
        }

        Type genericInterface = object.getClass()
                                      .getGenericInterfaces()[0];
        if (genericInterface instanceof ParameterizedType) {
            Type type = ((ParameterizedType) genericInterface).getActualTypeArguments()[argumentIndex];
            if (type instanceof ParameterizedType) {
                return (Class<?>) ((ParameterizedType)type).getRawType();
            } else {
                return (Class<?>) type;                
            }
        }
        else {
            String[] methodRef = resolveMethodRef(object, "Failed to resolve generic type arguments for " + object + " of type " + genericInterface.toString());
            try {
                if (methodRef[1].startsWith("lambda")) {
                    // This is an inline lambda expression, e.g. (String s) -> s.isEmpty()
                    String argumentType = jdk.internal.org.objectweb.asm.Type.getArgumentTypes(methodRef[2])[argumentIndex].getClassName();
                    return Class.forName(argumentType);                    
                } else {
                    // This was a function reference literal, e.g. String::isEmpty. 
                    // methodRef[0] will have a resource syntax, e.g. "java/lang/String"
                    return object.getClass().getClassLoader().loadClass(methodRef[0].replaceAll("/", "."));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve generic type arguments for " + object + " of type " + genericInterface
                    .toString(),
                                           e);
            }
        }
    }

    /**
     * Resolve the generic return type of an lambda instance (even though types are not reified in Java)<br/>
     * Example: If a class/method is handed a Function&lt;Foo, Bar> then resolveGenericLambdaReturnType will return Boolean.class:
     * <pre>
     * Function&lt;String, Boolean> myStrToBoolFunc = strArg -> true;
     * assertThat(Reflect.resolveGenericLambdaReturnType(myStrToBoolFunc), equalTo(Boolean.class));
     * </pre>
     */
    public static Class<?> resolveLambdaReturnType(Object object) {
        Objects.requireNonNull(object,
                               "object");
        String[] methodRef = resolveMethodRef(object, "Failed to resolve generic return type for " + object + " of type " + object.getClass()
                                                                                                                                  .toString());

        try {
            String returnType = jdk.internal.org.objectweb.asm.Type.getReturnType(methodRef[2])
                                                                   .getClassName();
            return Class.forName(returnType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve generic return type for " + object + " of type " + object.getClass()
                                                                                                                   .toString(),
                                       e);
        }
    }

    private static String[] resolveMethodRef(Object object, String message) {
        ConstantPool constantPool = getConstantPool(object, message);

        int at = 15;
        String[] methodRef = null;
        // Try and resolve the methodRef, but stop if advancing the at doesn't turn out a result
        while (methodRef == null && at < 100) {
            try {
                // This is pretty slow and somewhat idiotic, but works and gives us reified generics in Java 8
                methodRef = constantPool.getMemberRefInfoAt(at);
            } catch (IllegalArgumentException e) {
                // For every generic argument the index forward
                at += 1;
            }
        }
        if (methodRef == null) {
            throw new RuntimeException(message);
        }
        return methodRef;
    }

    private static ConstantPool getConstantPool(Object object, String message) {
        try {
            return (ConstantPool) getConstantPoolMethod.invoke(object.getClass());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(message, e);
        }
    }
}