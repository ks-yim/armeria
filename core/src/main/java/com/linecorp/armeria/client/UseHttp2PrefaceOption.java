/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import java.util.Arrays;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * Specifies on which protocols the client will send an HTTP/2 preface string
 * instead of an HTTP/1 upgrade request to negotiate the protocol version.
 */
public enum UseHttp2PrefaceOption {

    /**
     * Enables an HTTP/2 preface string with {@link SessionProtocol#HTTP}.
     */
    HTTP,

    /**
     * Enables an HTTP/2 preface string with {@link SessionProtocol#H2C}.
     */
    H2C,

    /**
     * Enables an HTTP/2 preface string with {@link SessionProtocol#HTTPS}.
     * This option has no effect unless {@link ClientFactoryOptions#useHttp2WithoutAlpn()}
     * is set to {@code false}.
     */
    HTTPS,

    /**
     * Enables an HTTP/2 preface string with {@link SessionProtocol#H2}.
     * This option has no effect unless {@link ClientFactoryOptions#useHttp2WithoutAlpn()}
     * is set to {@code false}.
     */
    H2;

    private static final Set<UseHttp2PrefaceOption> allOf =
            Arrays.stream(values()).collect(Sets.toImmutableEnumSet());

    private static final Set<UseHttp2PrefaceOption> noneOf = ImmutableSet.of();

    /**
     * Returns all {@link UseHttp2PrefaceOption}s.
     */
    public static Set<UseHttp2PrefaceOption> allOf() {
        return allOf;
    }

    /**
     * Returns empty {@link UseHttp2PrefaceOption}s.
     */
    public static Set<UseHttp2PrefaceOption> noneOf() {
        return noneOf;
    }
}
