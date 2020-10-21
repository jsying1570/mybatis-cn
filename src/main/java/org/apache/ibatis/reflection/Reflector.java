/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * 此类表示一组缓存的类定义信息，允许在属性名称和getter / setter方法之间轻松映射
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  // 记录了对应的 Class 类型
  private final Class<?> type;
  // 可读属性的名称集合。 即存在对应的 getter 方法的属性
  private final String[] readablePropertyNames;
  // 可写属性的名称集合。 即存在对应的 setter 方法的属性
  private final String[] writeablePropertyNames;
  // 记录了属性相应的 setter 方法， key 是属性的名称， value 是 Invoker 对象
  private final Map<String, Invoker> setMethods = new HashMap<>();
  // 记录了属性相应的 getter 方法， key 是属性的名称， value 是 Invoker 对象
  private final Map<String, Invoker> getMethods = new HashMap<>();
  // 记录了相应的 setter 方法参数类型， key 是属性名称， value 是参数类型
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  // 记录了相应的 getter 方法参数类型， key 是属性名称， value 是参数类型
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  // 默认构造方法
  private Constructor<?> defaultConstructor;
  // 记录所有属性名称的集合
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  /**
   * 构造方法
   *
   * @param clazz 需要缓存的对象
   */
  public Reflector(Class<?> clazz) {
    // 初始化 type 字段
    type = clazz;
    // 查找默认构造方法， 并赋值给 defaultConstructor
    addDefaultConstructor(clazz);
    // 处理 clazz 中的 getter 方法
    addGetMethods(clazz);
    // 处理 clazz 中的 setter 方法
    addSetMethods(clazz);
    // 处理没有 getter 和 setter 的方法
    addFields(clazz);
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  /**
   * 解析默认构造函数
   *
   * @param clazz 缓存对象的 Class 类型
   */
  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有的构造函数
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      // 获取参数为0的构造函数
      if (constructor.getParameterTypes().length == 0) {
        // 检查是否可以控制成员访问
        if (canControlMemberAccessible()) {
          try {
            // 设置访问为 true。 此时， 会取消 Java 语言的访问检查， 提高性能
            constructor.setAccessible(true);
          } catch (Exception e) {
            // Ignored. This is only a final precaution, nothing we can do.
          }
        }
        if (constructor.isAccessible()) {
          this.defaultConstructor = constructor;
        }
      }
    }
  }

  /**
   * 添加 Get 方法到类中
   * @param cls
   */
  private void addGetMethods(Class<?> cls) {
    // 注意该变量的类型， Map<String, List<Method>>， name 是String的类型， 但是 value 是 List
    // 说明会有同一个属性名称对应多个方法， 理解了这个， 在冲突解决时就好理解多了
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 类和其父类的所声明的所有方法
    Method[] methods = getClassMethods(cls);

    // 按照 JavaBean 规范查找 getter 方法， 并记录到 conflictingGetters 中
    for (Method method : methods) {
      // getter 方法中没有参数
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      // 获取方法名称， 含有 get 的方法， 名称长度大于 3
      // 含有 is 的方法， 名称长度大于2
      String name = method.getName();
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
        // 根据 JavaBean 规范， 获取属性名
        name = PropertyNamer.methodToProperty(name);
        // 添加方法到 conflictingGetters 中
        addMethodConflict(conflictingGetters, name, method);
      }
    }
    // 处理 conflictingGetters 集合， 防止冲突,其实主要是子类复写父类的时候，方法的返回值可以放大访问权限，导致一个属性有多个方法，此方法就是用来
    //解决这个，主要的思想就是判定类型是否是另外一个类型的父类型
    resolveGetterConflicts(conflictingGetters);
  }

    /**
     * 解决子类继承父类的方法， 但返回值不一致导致签名认定为是两个不一样的方法
     *
     * @param conflictingGetters
     */
    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        // 遍历 getter 方法的集合
        // 子类重写父类的方法时， 返回值可能会是父类方法返回值的子类， 因此， 会导致同一个属性有多个方法
        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            Method winner = null;
            String propName = entry.getKey();
            // 详情请看我的微博吧
            for (Method candidate : entry.getValue()) {
                if (winner == null) {
                    winner = candidate;
                    continue;
                }
                // winner 方法返回值
                Class<?> winnerType = winner.getReturnType();
                // candidate 方法返回值
              Class<?> candidateType = candidate.getReturnType();
                // 返回值相同
                if (candidateType.equals(winnerType)) {
                    // 返回值相同
                    if (!boolean.class.equals(candidateType)) {
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property "
                                        + propName + " in class " + winner.getDeclaringClass()
                                        + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                    } else if (candidate.getName().startsWith("is")) {
                        winner = candidate;
                    }
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    // OK getter type is descendant
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    winner = candidate;
                } else {
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property "
                                    + propName + " in class " + winner.getDeclaringClass()
                                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
            addGetMethod(propName, winner);
        }
    }

    /**
     * 添加 getter 到对象中
     *
     * @param name
     * @param method
     */
    private void addGetMethod(String name, Method method) {
        // 检查属性名是否合法
        if (isValidPropertyName(name)) {
            // 将属性名和对应的 Invoker添加到集合当中
            getMethods.put(name, new MethodInvoker(method));
            // 获取返回值的类型
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            // 将属性名及其getter 添加到 getTypes 集合中
            getTypes.put(name, typeToClass(returnType));
        }
    }

  /**
   * 添加 set方法
   *
   * @param cls
   */
  private void addSetMethods(Class<?> cls) {
    // 注意该变量的类型， Map<String, List<Method>>， name 是String的类型， 但是 value 是 List
    // 说明会有同一个属性名称对应多个方法
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    // 获取类和其父类的所声明的所有方法
    Method[] methods = getClassMethods(cls);
    // 按照 JavaBean 规范查找 setter 方法， 并记录到 conflictingSetters 中
    for (Method method : methods) {
      String name = method.getName();
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          // 获取属性名称
          name = PropertyNamer.methodToProperty(name);
          // 添加到集合中
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    resolveSetterConflicts(conflictingSetters);
  }

    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        // computeIfAbsent 检查map中是否存在Key值，不存在则存入；
        // 如果存在会检查value值是否为空，如果为空就会将K值赋给value
        List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
        list.add(method);
    }
  /**
   *  解决 setter 冲突
   */
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      ReflectionException exception = null;
      for (Method setter : setters) {
        Class<?> paramType = setter.getParameterTypes()[0];
        if (paramType.equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (exception == null) {
          try {
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }

  /**
   * 从两个 setter 中选择一个更适合的
   * @param setter1
   * @param setter2
   * @param property
   * @return
   */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    // paramType1 是 paramType2 的父类或接口。
    // paramType1 与 paramType2同一个类或接口
    // 谁是子类返回谁
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  /**
   * 将 Type 类型转化为制定的 Class
   * @param src 需要转换的Type
   * @return
   */
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance((Class<?>) componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  /**
   * 处理类中所有的字段， 并将处理后的信息添加到对应的集合
   * （setMethods, setTypes, getMethods, getTypes）
   *
   * @param clazz
   */
  private void addFields(Class<?> clazz) {
    // 获取所有生命的字段
    Field[] fields = clazz.getDeclaredFields();
    // 遍历
    for (Field field : fields) {
      if (canControlMemberAccessible()) {
        try {
          field.setAccessible(true);
        } catch (Exception e) {
          // Ignored. This is only a final precaution, nothing we can do.
        }
      }
      if (field.isAccessible()) {
        if (!setMethods.containsKey(field.getName())) {
          // issue #379 - removed the check for final because JDK 1.5 allows
          // modification of final fields through reflection (JSR-133). (JGB)
          // pr #16 - final static can only be set by the classloader
          // 获取字段的修饰符
          int modifiers = field.getModifiers();
          // 过滤掉 final 和 static
          if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
            // 添加到 setMethods 和 setTypes 集合中
            addSetField(field);
          }
        }
        // 当 getMethods 集合中不包含同名属性时
        if (!getMethods.containsKey(field.getName())) {
          // 添加到 getMethods 和 getTypes 集合中
          addGetField(field);
        }
      }
    }
    // 处理其父类的字段
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  /**
   * 添加 set 成员变量： setMethods 列表和 setTypes 列表对应都要添加
   * @param field
   */
  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 添加 get 成员变量： getMethods 列表和 getTypes 列表对应都要添加
   * @param field
   */
  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 判断是否为合格的 Property
   * @param name
   * @return
   */
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  /**
   * 该方法返回了包含有类和其父类的所声明的所有方法。
   * 使用该方法， 而不是 Class.getMethods(), 因为也需要查找private的方法
   *
   * @param cls 类
   * @return 包含有该类所有方法的数组
   */
  private Method[] getClassMethods(Class<?> cls) {
    // 用于记录该类中的全部方法的唯一签名， 及其 Method 对象
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = cls;
    // 类非空， 以及不等于 Object
    while (currentClass != null && currentClass != Object.class) {
      // 添加该类中的所有方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 记录接口方法， 因为类可能是抽象的
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      // 获取父类
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  /**
   * 为每个方法生成唯一的签名， 并记录到 uniqueMethods 集合中
   * @param uniqueMethods 方法存放的 Map
   * @param methods 方法
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      // 判断是否为桥接方法， 桥接方法不在我们需要的方法当中
      if (!currentMethod.isBridge()) {
        // 通过 getSignature 函数获取唯一的签名， 该签名具有唯一性
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 检查子类中是否已经添加过这个方法了， 如过已经添加了， 表示子类已经覆盖覆盖这个方法
        // 此时就不需要添加到集合中
        if (!uniqueMethods.containsKey(signature)) {
          if (canControlMemberAccessible()) {
            try {
              currentMethod.setAccessible(true);
            } catch (Exception e) {
              // Ignored. This is only a final precaution, nothing we can do.
            }
          }

          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 获取方法的全局唯一签名
   * 返回值类型#方法名:参数1,参数2,参数3...
   * @param method 方法
   * @return 签名
   */
  private String getSignature(Method method) {
    // 定义一个StringBuilder , 需要进行拼接的字符串， 在 MyBatis 中基本用这个
    StringBuilder sb = new StringBuilder();
    // 获取返回类型
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   * 检查是否可以控制成员访问，
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  /**
   * 获取缓存的类的默认构造方法
   * @return 默认构造方法
   */
  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  /**
   * 是否有默认构造方法
   */
  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  /**
   * 根据属性名， 获取 setter Invoker
   * @param propertyName 属性名
   * @return Invoker
   */
  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * 根据属性名， 获取 getter Invoker
   * @param propertyName 属性名
   * @return Invoker
   */
  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter
   * 获取 setter 的属性类型
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter
   * 获取 getter 的属性类型
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object
   *
   *  获取可读列表
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object
   *
   *  获取可写属性列表
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name
   * 根据名字查看类是否具有同名的可写属性
   * @param propertyName - 需要检查的属性名
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name
   * 根据名字查看类是否具有同名的可读属性
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  /**
   * 根据名称查找属性名
   * @param name
   * @return
   */
  public String   findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
