package com.mingo.convert;

import static java.text.MessageFormat.format;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.ClassPath;
import com.mingo.exceptions.ConversionException;
import com.mongodb.DBObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.reflections.util.ClasspathHelper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copyright 2012-2013 The Mingo Team
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
public class ConverterService {

    private Set<String> convertersPackages;

    private Set<Class<? extends Converter>> convertersClasses;

    private Map<Class<?>, Converter> cachedConverters = Maps.newHashMap();

    private Map<String, Object> specificConverters = Maps.newHashMap();

    private List<ClassLoader> classLoaders = Lists.newArrayList(ClasspathHelper.contextClassLoader(),
        ClasspathHelper.staticClassLoader());

    private static final String CONVERTER_ERROR_MSG = "can't be registered more then one converter for {0} type";

    private static final String CONVERTER_LOAD_ERROR = "cannot load converters from package: {0}";

    private static final String CONVERTER_INSTANTIATION_ERROR =
        "converter instantiation process has failed. Converter class: {0}";


    /**
     * Constructor with parameters.
     *
     * @param convertersPackages converters packages
     */
    public ConverterService(String convertersPackages) {
        if (StringUtils.isNotBlank(convertersPackages)) {
            convertersPackages = convertersPackages.replaceAll("\\s", "");
            this.convertersPackages = Sets.newHashSet(StringUtils.split(convertersPackages, ","));
            initConvertersClasses();
        }

    }

    /**
     * Gets converter for specified type.
     *
     * @param aClass class
     * @param <T>    the type of the class modeled by this {@code Class} object
     * @return converter for specified type. null if converter not found.
     */
    public <T> Converter<T> lookup(final Class<T> aClass) {
        if (CollectionUtils.isEmpty(convertersClasses)) {
            return null;
        }
        if (cachedConverters.containsKey(aClass)) {
            return cachedConverters.get(aClass);
        }

        Set<Class<? extends Converter>> converters = getConverters(aClass);
        if (CollectionUtils.isNotEmpty(converters)) {
            if (converters.size() > 1) {
                throw new RuntimeException(format(CONVERTER_ERROR_MSG, aClass));
            }
            try {
                Converter converter = converters.iterator().next().newInstance();
                cachedConverters.put(aClass, converter);
                return converter;
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(format(CONVERTER_INSTANTIATION_ERROR, aClass), e);
            }
        }
        return null;
    }

    /**
     * Invokes <code>converterMethod</code> method from <code>converterClass</code>
     * to convert <code>source</code> DBObject to custom object.
     *
     * @param source          DBObject
     * @param converterClass  converter class name
     * @param converterMethod converter method name to invoke
     * @param <T>             the type of the class modeled by this {@code Class} object
     * @return converted object
     */
    public <T> T convertByMethod(DBObject source,
                                 String converterClass, String converterMethod) {
        Validate.notBlank(converterClass, "converter class name cannot be null or empty");
        Validate.notBlank(converterMethod, "converter method name cannot be null or empty");
        T result;
        try {
            Object converterInstance;
            if (specificConverters.containsKey(converterClass)) {
                converterInstance = specificConverters.get(converterClass);
            } else {
                converterInstance = Class.forName(converterClass).newInstance();
                specificConverters.put(converterClass, converterInstance);
            }
            Method method = Class.forName(converterClass).getDeclaredMethod(converterMethod, DBObject.class);
            result = (T) method.invoke(converterInstance, source);
        } catch (Exception e) {
            throw new ConversionException(e);
        }
        return result;
    }

    private void initConvertersClasses() {
        if (CollectionUtils.isNotEmpty(convertersPackages)) {
            convertersClasses = new HashSet<>();
            for (String converterPackage : convertersPackages) {
                convertersClasses.addAll(getConvertersClasses(converterPackage));
            }
        }
    }

    private Set<Class<? extends Converter>> getConvertersClasses(String converterPackage) {
        Set<Class<? extends Converter>> classes = Collections.emptySet();
        for (ClassLoader classLoader : classLoaders) {
            classes = getConvertersClasses(converterPackage, classLoader);
            if (CollectionUtils.isNotEmpty(classes)) {
                break;
            }
        }
        return classes;
    }

    private Set<Class<? extends Converter>> getConvertersClasses(String converterPackage, ClassLoader classLoader) {
        Set<Class<? extends Converter>> classes = Collections.emptySet();
        try {
            ClassPath classPath = ClassPath.from(classLoader);
            Set<com.google.common.reflect.ClassPath.ClassInfo> classInfos =
                classPath.getTopLevelClassesRecursive(converterPackage);
            if (CollectionUtils.isNotEmpty(classInfos)) {
                classes = Sets.newHashSet();
                for (com.google.common.reflect.ClassPath.ClassInfo classInfo : classInfos) {
                    Class converterClass = Class.forName(classInfo.getName());
                    if (Converter.class.isAssignableFrom(converterClass) &&
                        classInfo.getName().contains(converterPackage)) {
                        classes.add(converterClass);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(format(CONVERTER_LOAD_ERROR, converterPackage), e);
        }
        return classes;
    }

    private <T> Set<Class<? extends Converter>> getConverters(final Class<T> aClass) {
        Set<Class<? extends Converter>> suitableConverters = new HashSet<>();
        for (Class<? extends Converter> converterClass : convertersClasses) {
            if (converterClass.getGenericInterfaces().length > 0 &&
                converterClass.getGenericInterfaces()[0] instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) converterClass.getGenericInterfaces()[0];
                if (pt.getActualTypeArguments().length > 0 && pt.getActualTypeArguments()[0] instanceof Class) {
                    Class<?> at = (Class<?>) pt.getActualTypeArguments()[0];
                    if (aClass.equals(at)) {
                        suitableConverters.add(converterClass);
                    }
                }
            }
        }
        return suitableConverters;
    }

}
