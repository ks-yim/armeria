/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.util.ObjectCollectingUtil.collectFrom;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.AggregationStrategy;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.ResolverContext;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.ByteArrayResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.annotation.HttpFileResponseConverterFunction;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;
import com.linecorp.armeria.server.annotation.ServiceName;
import com.linecorp.armeria.server.annotation.StringResponseConverterFunction;

/**
 * An {@link HttpService} which is defined by a {@link Path} or HTTP method annotations.
 * This class is not supposed to be instantiated by a user. Please check out the documentation
 * <a href="https://armeria.dev/docs/server-annotated-service">Annotated HTTP Service</a> to use this.
 */
public final class AnnotatedService implements HttpService {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedService.class);

    /**
     * The CGLIB class separator: {@code "$$"}.
     */
    private static final String CGLIB_CLASS_SEPARATOR = "$$";

    /**
     * A default {@link ResponseConverterFunction}s.
     */
    private static final List<ResponseConverterFunction> defaultResponseConverters =
            ImmutableList.of(new JacksonResponseConverterFunction(),
                             new StringResponseConverterFunction(),
                             new ByteArrayResponseConverterFunction(),
                             new HttpFileResponseConverterFunction());

    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    static final List<ResponseConverterFunctionProvider> responseConverterFunctionProviders =
            ImmutableList.copyOf(ServiceLoader.load(ResponseConverterFunctionProvider.class,
                                                    AnnotatedService.class.getClassLoader()));

    static {
        if (!responseConverterFunctionProviders.isEmpty()) {
            logger.debug("Available {}s: {}", ResponseConverterFunctionProvider.class.getSimpleName(),
                         responseConverterFunctionProviders);
        }
    }

    private final Object object;
    private final Method method;
    private final MethodHandle methodHandle;
    @Nullable
    private final MethodHandle callKotlinSuspendingMethod;
    private final boolean isKotlinSuspendingMethod;
    private final List<AnnotatedValueResolver> resolvers;

    private final AggregationStrategy aggregationStrategy;
    private final ExceptionHandlerFunction exceptionHandler;
    private final ResponseConverterFunction responseConverter;

    private final Route route;
    private final ResponseHeaders defaultHttpHeaders;
    private final HttpHeaders defaultHttpTrailers;

    private final ResponseType responseType;
    private final boolean useBlockingTaskExecutor;
    private final String serviceName;
    private final boolean serviceNameSetByAnnotation;

    AnnotatedService(Object object, Method method,
                     List<AnnotatedValueResolver> resolvers,
                     List<ExceptionHandlerFunction> exceptionHandlers,
                     List<ResponseConverterFunction> responseConverters,
                     Route route,
                     ResponseHeaders defaultHttpHeaders,
                     HttpHeaders defaultHttpTrailers,
                     boolean useBlockingTaskExecutor) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        checkArgument(!method.isVarArgs(), "%s#%s declared to take a variable number of arguments",
                      method.getDeclaringClass().getSimpleName(), method.getName());
        isKotlinSuspendingMethod = KotlinUtil.isSuspendingFunction(method);
        this.resolvers = requireNonNull(resolvers, "resolvers");
        exceptionHandler =
                new CompositeExceptionHandlerFunction(object.getClass().getSimpleName(), method.getName(),
                                                      requireNonNull(exceptionHandlers, "exceptionHandlers"));
        responseConverter = responseConverter(
                method, requireNonNull(responseConverters, "responseConverters"), exceptionHandler);
        aggregationStrategy = AggregationStrategy.from(resolvers);
        this.route = requireNonNull(route, "route");

        this.defaultHttpHeaders = requireNonNull(defaultHttpHeaders, "defaultHttpHeaders");
        this.defaultHttpTrailers = requireNonNull(defaultHttpTrailers, "defaultHttpTrailers");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        final Class<?> returnType = method.getReturnType();
        if (HttpResponse.class.isAssignableFrom(returnType)) {
            responseType = ResponseType.HTTP_RESPONSE;
        } else if (CompletionStage.class.isAssignableFrom(returnType)) {
            responseType = ResponseType.COMPLETION_STAGE;
        } else if (isKotlinSuspendingMethod) {
            responseType = ResponseType.KOTLIN_COROUTINES;
        } else if (ScalaUtil.isScalaFuture(returnType)) {
            responseType = ResponseType.SCALA_FUTURE;
        } else {
            responseType = ResponseType.OTHER_OBJECTS;
        }
        callKotlinSuspendingMethod = KotlinUtil.getCallKotlinSuspendingMethod();

        ServiceName serviceName = AnnotationUtil.findFirst(method, ServiceName.class);
        if (serviceName == null) {
            serviceName = AnnotationUtil.findFirst(object.getClass(), ServiceName.class);
        }
        if (serviceName != null) {
            this.serviceName = serviceName.value();
            serviceNameSetByAnnotation = true;
        } else {
            this.serviceName = getUserClass(object.getClass()).getName();
            serviceNameSetByAnnotation = false;
        }

        this.method.setAccessible(true);
        // following must be called only after method.setAccessible(true)
        methodHandle = asMethodHandle(method, object);
    }

    private static ResponseConverterFunction responseConverter(
            Method method, List<ResponseConverterFunction> responseConverters,
            ExceptionHandlerFunction exceptionHandler) {

        final Type actualType;
        if (HttpResult.class.isAssignableFrom(method.getReturnType())) {
            final ParameterizedType type = (ParameterizedType) method.getGenericReturnType();
            warnIfHttpResponseArgumentExists(type, type);
            actualType = type.getActualTypeArguments()[0];
        } else {
            actualType = method.getGenericReturnType();
        }

        final ImmutableList<ResponseConverterFunction> backingConverters =
                ImmutableList
                        .<ResponseConverterFunction>builder()
                        .addAll(responseConverters)
                        .addAll(defaultResponseConverters)
                        .build();
        final ResponseConverterFunction responseConverter = new CompositeResponseConverterFunction(
                ImmutableList
                        .<ResponseConverterFunction>builder()
                        .addAll(backingConverters)
                        // It is the last converter to try to convert the result object into an HttpResponse
                        // after aggregating the published object from a Publisher or Stream.
                        .add(new AggregatedResponseConverterFunction(
                                new CompositeResponseConverterFunction(backingConverters), exceptionHandler))
                        .build());

        for (final ResponseConverterFunctionProvider provider : responseConverterFunctionProviders) {
            final ResponseConverterFunction func =
                    provider.createResponseConverterFunction(actualType, responseConverter, exceptionHandler);
            if (func != null) {
                return func;
            }
        }

        return responseConverter;
    }

    private static void warnIfHttpResponseArgumentExists(Type returnType, ParameterizedType type) {
        for (final Type arg : type.getActualTypeArguments()) {
            if (arg instanceof ParameterizedType) {
                warnIfHttpResponseArgumentExists(returnType, (ParameterizedType) arg);
            } else if (arg instanceof Class) {
                final Class<?> clazz = (Class<?>) arg;
                if (HttpResponse.class.isAssignableFrom(clazz) ||
                    AggregatedHttpResponse.class.isAssignableFrom(clazz)) {
                    logger.warn("{} in the return type '{}' may take precedence over {}.",
                                clazz.getSimpleName(), returnType, HttpResult.class.getSimpleName());
                }
            }
        }
    }

    public String serviceName() {
        return serviceName;
    }

    public boolean serviceNameSetByAnnotation() {
        return serviceNameSetByAnnotation;
    }

    public String methodName() {
        return method.getName();
    }

    Object object() {
        return object;
    }

    Method method() {
        return method;
    }

    List<AnnotatedValueResolver> annotatedValueResolvers() {
        return resolvers;
    }

    Route route() {
        return route;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(serve0(ctx, req));
    }

    /**
     * Executes the service method in different ways regarding its return type and whether the request is
     * required to be aggregated. If the return type of the method is not a {@link CompletionStage} or
     * {@link HttpResponse}, it will be executed in the blocking task executor.
     */
    private CompletionStage<HttpResponse> serve0(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<AggregatedHttpRequest> f;
        if (AggregationStrategy.aggregationRequired(aggregationStrategy, req)) {
            f = req.aggregate();
        } else {
            f = CompletableFuture.completedFuture(null);
        }

        ctx.mutateAdditionalResponseHeaders(mutator -> mutator.add(defaultHttpHeaders));
        ctx.mutateAdditionalResponseTrailers(mutator -> mutator.add(defaultHttpTrailers));

        switch (responseType) {
            case HTTP_RESPONSE:
                final Function<AggregatedHttpRequest, HttpResponse> httpResponseApplyFunction =
                        msg -> new ExceptionFilteredHttpResponse(
                                ctx, req, (HttpResponse) invoke(ctx, req, msg), exceptionHandler);
                if (useBlockingTaskExecutor) {
                    return f.thenApplyAsync(httpResponseApplyFunction, ctx.blockingTaskExecutor());
                } else {
                    return f.thenApply(httpResponseApplyFunction);
                }

            case COMPLETION_STAGE:
            case KOTLIN_COROUTINES:
            case SCALA_FUTURE:
                final CompletableFuture<?> composedFuture;
                final AtomicReference<CompletionStage<?>> upstreamStage = new AtomicReference<>();
                if (useBlockingTaskExecutor) {
                    composedFuture = f.thenComposeAsync(msg -> {
                        final CompletionStage<?> result =
                                toCompletionStage(invoke(ctx, req, msg), ctx.blockingTaskExecutor());
                        upstreamStage.set(result);
                        return result;
                    }, ctx.blockingTaskExecutor());
                } else {
                    composedFuture = f.thenCompose(msg -> {
                        final CompletionStage<?> result =
                                toCompletionStage(invoke(ctx, req, msg), ctx.eventLoop());
                        upstreamStage.set(result);
                        return result;
                    });
                }

                final CompletableFuture<HttpResponse> resFuture = composedFuture.handle((result, cause) -> {
                    if (cause != null) {
                        return handleExceptionWithContext(exceptionHandler, ctx, req, cause);
                    }
                    return convertResponse(ctx, req, null, result, HttpHeaders.of());
                });
                // Propagate cancellation to the upstream.
                resFuture.handle((ignored, cause) -> {
                    if (cause != null) {
                        final CompletionStage<?> upstream = upstreamStage.get();
                        if (upstream != null) {
                            upstream.toCompletableFuture().completeExceptionally(cause);
                        }
                    }
                    return null;
                });
                return resFuture;
            default:
                final Function<AggregatedHttpRequest, HttpResponse> defaultApplyFunction =
                        msg -> convertResponse(ctx, req, null, invoke(ctx, req, msg), HttpHeaders.of());
                if (useBlockingTaskExecutor) {
                    return f.thenApplyAsync(defaultApplyFunction, ctx.blockingTaskExecutor());
                } else {
                    return f.thenApply(defaultApplyFunction);
                }
        }
    }

    /**
     * Invokes the service method with arguments.
     */
    @Nullable
    private Object invoke(ServiceRequestContext ctx, HttpRequest req,
                          @Nullable AggregatedHttpRequest aggregatedRequest) {
        try (SafeCloseable ignored = ctx.push()) {
            final ResolverContext resolverContext = new ResolverContext(ctx, req, aggregatedRequest);
            final Object[] arguments = AnnotatedValueResolver.toArguments(resolvers, resolverContext);
            if (isKotlinSuspendingMethod) {
                assert callKotlinSuspendingMethod != null;
                return callKotlinSuspendingMethod.invoke(
                        method, object, arguments,
                        useBlockingTaskExecutor ? ctx.blockingTaskExecutor() : ctx.eventLoop(),
                        ctx);
            } else {
                return methodHandle.invoke(arguments);
            }
        } catch (Throwable cause) {
            return handleExceptionWithContext(exceptionHandler, ctx, req, cause);
        }
    }

    private static HttpResponse handleExceptionWithContext(ExceptionHandlerFunction exceptionHandler,
                                                           ServiceRequestContext ctx, HttpRequest req,
                                                           Throwable cause) {
        try (SafeCloseable ignored = ctx.push()) {
            return exceptionHandler.handleException(ctx, req, cause);
        }
    }

    /**
     * Converts the specified {@code result} to an {@link HttpResponse}.
     */
    private HttpResponse convertResponse(ServiceRequestContext ctx, HttpRequest req,
                                         @Nullable HttpHeaders headers, @Nullable Object result,
                                         HttpHeaders trailers) {
        final ResponseHeaders newHeaders;
        final HttpHeaders newTrailers;
        if (result instanceof HttpResult) {
            final HttpResult<?> httpResult = (HttpResult<?>) result;
            newHeaders = setHttpStatus(addNegotiatedResponseMediaType(ctx, httpResult.headers()));
            result = httpResult.content();
            newTrailers = httpResult.trailers();
        } else {
            newHeaders = setHttpStatus(
                    headers == null ? addNegotiatedResponseMediaType(ctx, HttpHeaders.of())
                                    : ResponseHeaders.builder().add(headers));
            newTrailers = trailers;
        }

        if (result instanceof HttpResponse) {
            return new ExceptionFilteredHttpResponse(ctx, req, (HttpResponse) result, exceptionHandler);
        }
        if (result instanceof AggregatedHttpResponse) {
            return ((AggregatedHttpResponse) result).toHttpResponse();
        }
        if (result instanceof CompletionStage) {
            return HttpResponse.from(
                    ((CompletionStage<?>) result)
                            .thenApply(object -> convertResponse(ctx, req, newHeaders, object,
                                                                 newTrailers))
                            .exceptionally(
                                    cause -> handleExceptionWithContext(exceptionHandler, ctx, req, cause)));
        }

        try (SafeCloseable ignored = ctx.push()) {
            final HttpResponse response =
                    responseConverter.convertResponse(ctx, newHeaders, result, newTrailers);
            if (response instanceof HttpResponseWriter) {
                // A streaming response has more chance to get an exception.
                return new ExceptionFilteredHttpResponse(ctx, req, response, exceptionHandler);
            } else {
                return response;
            }
        } catch (Exception cause) {
            return handleExceptionWithContext(exceptionHandler, ctx, req, cause);
        }
    }

    private static ResponseHeadersBuilder addNegotiatedResponseMediaType(ServiceRequestContext ctx,
                                                                         HttpHeaders headers) {

        final MediaType negotiatedResponseMediaType = ctx.negotiatedResponseMediaType();
        if (negotiatedResponseMediaType == null || headers.contentType() != null) {
            // Do not overwrite 'content-type'.
            return ResponseHeaders.builder()
                                  .add(headers);
        }

        return ResponseHeaders.builder()
                              .add(headers)
                              .contentType(negotiatedResponseMediaType);
    }

    private ResponseHeaders setHttpStatus(ResponseHeadersBuilder headers) {
        if (headers.contains(HttpHeaderNames.STATUS)) {
            // Do not overwrite HTTP status.
            return headers.build();
        }

        final HttpStatus defaultHttpStatus = defaultHttpHeaders.status();
        return headers.status(defaultHttpStatus).build();
    }

    /**
     * Converts the specified {@code obj} with {@link CompletableFuture}.
     */
    private static CompletionStage<?> toCompletionStage(@Nullable Object obj, ExecutorService executor) {
        if (obj instanceof CompletionStage) {
            return (CompletionStage<?>) obj;
        }
        if (obj != null && ScalaUtil.isScalaFuture(obj.getClass())) {
            return ScalaUtil.FutureConverter.toCompletableFuture((scala.concurrent.Future<?>) obj, executor);
        }
        return CompletableFuture.completedFuture(obj);
    }

    /**
     * Returns a {@link Function} which produces an {@link HttpService} wrapped with an
     * {@link ExceptionFilteredHttpResponseDecorator}.
     */
    public Function<? super HttpService, ? extends HttpService> exceptionHandlingDecorator() {
        return ExceptionFilteredHttpResponseDecorator::new;
    }

    /**
     * Intercepts an {@link HttpResponse} and wraps the response with an {@link ExceptionFilteredHttpResponse}
     * if it is not an instance of {@link ExceptionFilteredHttpResponse}. This decorator will make an
     * {@link Exception} to be handled by {@link ExceptionHandlerFunction}s even if the exception is raised
     * from a decorator.
     */
    private class ExceptionFilteredHttpResponseDecorator extends SimpleDecoratingHttpService {

        ExceptionFilteredHttpResponseDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            try {
                final HttpResponse response = unwrap().serve(ctx, req);
                if (response instanceof ExceptionFilteredHttpResponse) {
                    return response;
                }
                return new ExceptionFilteredHttpResponse(ctx, req, response, exceptionHandler);
            } catch (Exception cause) {
                return handleExceptionWithContext(exceptionHandler, ctx, req, cause);
            }
        }
    }

    /**
     * Intercepts a {@link Throwable} raised from {@link HttpResponse} and then rewrites it as an
     * {@link HttpResponseException} by {@link ExceptionHandlerFunction}.
     */
    private static class ExceptionFilteredHttpResponse extends FilteredHttpResponse {

        private final ServiceRequestContext ctx;
        private final HttpRequest req;
        private final ExceptionHandlerFunction exceptionHandler;

        // TODO(hyangtack) Remove this class if we could provide a better way to handle an exception
        //                 without this class. See https://github.com/line/armeria/issues/1514.
        ExceptionFilteredHttpResponse(ServiceRequestContext ctx, HttpRequest req,
                                      HttpResponse delegate, ExceptionHandlerFunction exceptionHandler) {
            super(delegate);
            this.ctx = ctx;
            this.req = req;
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        protected HttpObject filter(HttpObject obj) {
            return obj;
        }

        @Override
        protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
            if (cause instanceof HttpResponseException) {
                // Do not convert again if it has been already converted.
                return cause;
            }
            return HttpResponseException.of(handleExceptionWithContext(exceptionHandler, ctx, req, cause));
        }
    }

    /**
     * An {@link ExceptionHandlerFunction} which wraps a list of {@link ExceptionHandlerFunction}s.
     */
    private static final class CompositeExceptionHandlerFunction implements ExceptionHandlerFunction {

        private final String className;
        private final String methodName;
        private final List<ExceptionHandlerFunction> functions;

        CompositeExceptionHandlerFunction(String className, String methodName,
                                          List<ExceptionHandlerFunction> functions) {
            this.className = className;
            this.methodName = methodName;
            this.functions = ImmutableList.copyOf(functions);
        }

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            final Throwable peeledCause = Exceptions.peel(cause);

            if (Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.ALL &&
                logger.isWarnEnabled()) {
                logger.warn("{} Exception raised by method '{}' in '{}':",
                            ctx, methodName, className, peeledCause);
            }

            for (final ExceptionHandlerFunction func : functions) {
                try {
                    final HttpResponse response = func.handleException(ctx, req, peeledCause);
                    // Check the return value just in case, then pass this exception to the default handler
                    // if it is null.
                    if (response == null) {
                        break;
                    }
                    return response;
                } catch (FallthroughException ignore) {
                    // Do nothing.
                } catch (Exception e) {
                    logger.warn("{} Unexpected exception from an exception handler {}:",
                                ctx, func.getClass().getName(), e);
                }
            }

            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * A response converter implementation which creates an {@link HttpResponse} with
     * the objects published from a {@link Publisher} or {@link Stream}.
     */
    private static final class AggregatedResponseConverterFunction implements ResponseConverterFunction {

        private final ResponseConverterFunction responseConverter;
        private final ExceptionHandlerFunction exceptionHandler;

        AggregatedResponseConverterFunction(ResponseConverterFunction responseConverter,
                                            ExceptionHandlerFunction exceptionHandler) {
            this.responseConverter = responseConverter;
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            final CompletableFuture<?> f;
            if (result instanceof Publisher) {
                f = collectFrom((Publisher<Object>) result);
            } else if (result instanceof Stream) {
                f = collectFrom((Stream<Object>) result, ctx.blockingTaskExecutor());
            } else {
                return ResponseConverterFunction.fallthrough();
            }

            assert f != null;
            return HttpResponse.from(f.handle((aggregated, cause) -> {
                if (cause != null) {
                    return handleExceptionWithContext(exceptionHandler, ctx, ctx.request(), cause);
                }
                try {
                    return responseConverter.convertResponse(ctx, headers, aggregated, trailers);
                } catch (Exception e) {
                    return handleExceptionWithContext(exceptionHandler, ctx, ctx.request(), e);
                }
            }));
        }
    }

    /**
     * Returns the user-defined class for the given class: usually simply the given class,
     * but the original class in case of a CGLIB-generated subclass.
     */
    private static Class<?> getUserClass(Class<?> clazz) {
        // Forked from https://github.com/spring-projects/spring-framework/blob/1565f4b83e7c48eeec9dc74f7eb042dce4dbb49a/spring-core/src/main/java/org/springframework/util/ClassUtils.java#L896-L904
        if (clazz.getName().contains(CGLIB_CLASS_SEPARATOR)) {
            final Class<?> superclass = clazz.getSuperclass();
            if (superclass != null && superclass != Object.class) {
                return superclass;
            }
        }
        return clazz;
    }

    /**
     * Response type classification of the annotated {@link Method}.
     */
    private enum ResponseType {
        HTTP_RESPONSE, COMPLETION_STAGE, KOTLIN_COROUTINES, SCALA_FUTURE, OTHER_OBJECTS
    }

    /**
     * Converts {@link Method} to {@link MethodHandle}, optionally accepting {@code object} instance of the
     * declaring class in case of non-static methods. Result {@link MethodHandle} must be assigned to
     * a {@code final} field in order to enable Java compiler optimizations.
     * @param method the {@link Method} to be converted to a {@link MethodHandle}
     * @param object an instance of declaring class for non-static methods, or {@link null} for static methods
     * @return a {@link MethodHandle} corresponding to the supplied {@link Method}
     */
    private static MethodHandle asMethodHandle(Method method, @Nullable Object object) {
        MethodHandle methodHandle;
        try {
            // an investigation showed no difference in performance between the MethodHandle
            // obtained via either MethodHandles.Lookup#unreflect or MethodHandles.Lookup#findVirtual
            methodHandle = lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            // this is extremely unlikely considering that we've already executed method.setAccessible(true)
            throw new RuntimeException(e);
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            // bind non-static methods to an instance of the declaring class
            methodHandle = methodHandle.bindTo(requireNonNull(object, "object"));
        }
        final int parameterCount = method.getParameterCount();
        // allows MethodHandle accepting an Object[] argument and
        // spreading its elements as positional arguments
        return methodHandle.asSpreader(Object[].class, parameterCount);
    }
}
