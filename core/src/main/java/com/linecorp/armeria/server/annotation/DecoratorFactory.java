/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.DependencyInjector;

/**
 * Specifies a {@link DecoratorFactoryFunction} class which is a factory to create a decorator.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface DecoratorFactory {

    /**
     * {@link DecoratorFactoryFunction} implementation type. The specified class must either have
     * an accessible default constructor or get injected by {@link DependencyInjector}
     * depending on its {@link #mode()}.
     */
    Class<? extends DecoratorFactoryFunction<?>> value();

    /**
     * The instance {@link CreationMode} of {@link DecoratorFactoryFunction} specified in {@link #value()}.
     */
    CreationMode mode() default CreationMode.REFLECTION;
}
