/**
 * Copyright (c) 2012-2019 Nikita Koksharov
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.namespace.Namespace;

public class ScannerEngine {

    private static final Logger log = LoggerFactory.getLogger(ScannerEngine.class);

    private static final List<? extends AnnotationScanner> annotations =
            Arrays.asList(new OnConnectScanner(), new OnDisconnectScanner(), new OnEventScanner());


    /**
     * 查找类似的方法
     *
     * @param objectClazz 类对象
     * @param method      方法
     * @return 方法
     */
    private Method findSimilarMethod(Class<?> objectClazz, Method method) {
        Method[] methods = objectClazz.getDeclaredMethods();
        for (Method m : methods) {
            if (isEquals(m, method)) {
                return m;
            }
        }
        return null;
    }


    /**
     * 扫描
     *
     * @param namespace 命名空间
     * @param object    对象
     * @param clazz     类
     * @throws IllegalArgumentException 非法参数异常
     */
    public void scan(Namespace namespace, Object object, Class<?> clazz)
            throws IllegalArgumentException {
        Method[] methods = clazz.getDeclaredMethods();

        if (!clazz.isAssignableFrom(object.getClass())) {
            for (Method method : methods) {
                for (AnnotationScanner annotationScanner : annotations) {
                    Annotation ann = method.getAnnotation(annotationScanner.getScanAnnotation());
                    if (ann != null) {
                        // 校验类型合法性
                        annotationScanner.validate(method, clazz);
                        // 查询相似的方法
                        Method m = findSimilarMethod(object.getClass(), method);
                        if (m != null) {
                            // 添加监听器
                            annotationScanner.addListener(namespace, object, m, ann);
                        } else {
                            log.warn("Method similar to " + method.getName() + " can't be found in " + object.getClass());
                        }

                    }
                }
            }
        } else {

            for (Method method : methods) {
                for (AnnotationScanner annotationScanner : annotations) {
                    Annotation ann = method.getAnnotation(annotationScanner.getScanAnnotation());
                    if (ann != null) {
                        annotationScanner.validate(method, clazz);
                        makeAccessible(method);
                        annotationScanner.addListener(namespace, object, method, ann);
                    }
                }
            }

            if (clazz.getSuperclass() != null) {
                scan(namespace, object, clazz.getSuperclass());
            } else if (clazz.isInterface()) {
                for (Class<?> superIfc : clazz.getInterfaces()) {
                    scan(namespace, object, superIfc);
                }
            }

        }

    }


    private boolean isEquals(Method method1, Method method2) {
        if (!method1.getName().equals(method2.getName())
                || !method1.getReturnType().equals(method2.getReturnType())) {
            return false;
        }

        return Arrays.equals(method1.getParameterTypes(), method2.getParameterTypes());
    }

    private void makeAccessible(Method method) {
        if ((!Modifier.isPublic(method.getModifiers()) || !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                && !method.isAccessible()) {
            method.setAccessible(true);
        }
    }

}
