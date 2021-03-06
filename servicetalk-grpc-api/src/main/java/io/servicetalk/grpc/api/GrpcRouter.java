/*
 * Copyright © 2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.grpc.api;

import io.servicetalk.concurrent.GracefulAutoCloseable;
import io.servicetalk.concurrent.api.AsyncCloseable;
import io.servicetalk.concurrent.api.AsyncCloseables;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.CompositeCloseable;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.grpc.api.GrpcRoutes.BlockingRequestStreamingRoute;
import io.servicetalk.grpc.api.GrpcRoutes.BlockingResponseStreamingRoute;
import io.servicetalk.grpc.api.GrpcRoutes.BlockingRoute;
import io.servicetalk.grpc.api.GrpcRoutes.BlockingStreamingRoute;
import io.servicetalk.grpc.api.GrpcRoutes.RequestStreamingRoute;
import io.servicetalk.grpc.api.GrpcRoutes.ResponseStreamingRoute;
import io.servicetalk.grpc.api.GrpcRoutes.Route;
import io.servicetalk.grpc.api.GrpcRoutes.StreamingRoute;
import io.servicetalk.grpc.api.GrpcServiceFactory.ServerBinder;
import io.servicetalk.grpc.api.GrpcUtils.GrpcStatusUpdater;
import io.servicetalk.http.api.BlockingHttpService;
import io.servicetalk.http.api.HttpApiConversions.ServiceAdapterHolder;
import io.servicetalk.http.api.HttpDeserializer;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpPayloadWriter;
import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpSerializer;
import io.servicetalk.http.api.HttpService;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.api.StreamingHttpService;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.api.ServerContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.grpc.api.GrpcRouteConversions.toAsyncCloseable;
import static io.servicetalk.grpc.api.GrpcRouteConversions.toRequestStreamingRoute;
import static io.servicetalk.grpc.api.GrpcRouteConversions.toResponseStreamingRoute;
import static io.servicetalk.grpc.api.GrpcRouteConversions.toRoute;
import static io.servicetalk.grpc.api.GrpcRouteConversions.toStreaming;
import static io.servicetalk.grpc.api.GrpcUtils.newErrorResponse;
import static io.servicetalk.grpc.api.GrpcUtils.newResponse;
import static io.servicetalk.grpc.api.GrpcUtils.readGrpcMessageEncoding;
import static io.servicetalk.grpc.api.GrpcUtils.setStatus;
import static io.servicetalk.grpc.api.GrpcUtils.uncheckedCast;
import static io.servicetalk.http.api.HttpApiConversions.toStreamingHttpService;
import static io.servicetalk.http.api.HttpExecutionStrategies.noOffloadsStrategy;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

/**
 * A router that can route <a href="https://www.grpc.io">gRPC</a> requests to a user provided
 * implementation of a <a href="https://www.grpc.io">gRPC</a> method.
 */
final class GrpcRouter {

    private final Map<String, RouteProvider> routes;
    private final Map<String, RouteProvider> streamingRoutes;
    private final Map<String, RouteProvider> blockingRoutes;
    private final Map<String, RouteProvider> blockingStreamingRoutes;

    private static final GrpcStatus STATUS_UNIMPLEMENTED = GrpcStatus.fromCodeValue(GrpcStatusCode.OK.value());
    private static final StreamingHttpService notFound = (ctx, request, responseFactory) -> {
        final StreamingHttpResponse response = responseFactory.ok();
        response.version(request.version());
        response.transformRaw(new GrpcStatusUpdater(ctx.executionContext().bufferAllocator(), STATUS_UNIMPLEMENTED));
        return succeeded(response);
    };

    private GrpcRouter(final Map<String, RouteProvider> routes,
                       final Map<String, RouteProvider> streamingRoutes,
                       final Map<String, RouteProvider> blockingRoutes,
                       final Map<String, RouteProvider> blockingStreamingRoutes) {
        this.routes = unmodifiableMap(routes);
        this.streamingRoutes = unmodifiableMap(streamingRoutes);
        this.blockingRoutes = unmodifiableMap(blockingRoutes);
        this.blockingStreamingRoutes = unmodifiableMap(blockingStreamingRoutes);
    }

    Single<ServerContext> bind(final ServerBinder binder, final ExecutionContext executionContext) {
        final Map<String, StreamingHttpService> allRoutes = new HashMap<>();
        populateRoutes(executionContext, allRoutes, routes);
        populateRoutes(executionContext, allRoutes, streamingRoutes);
        populateRoutes(executionContext, allRoutes, blockingRoutes);
        populateRoutes(executionContext, allRoutes, blockingStreamingRoutes);

        // TODO: Optimize to bind a specific programming model service based on routes
        return binder.bindStreaming((ctx, request, responseFactory) -> {
            StreamingHttpService service;
            if (request.method() != HttpRequestMethod.POST || (service = allRoutes.get(request.path())) == null) {
                return notFound.handle(ctx, request, responseFactory);
            } else {
                return service.handle(ctx, request, responseFactory);
            }
        });
    }

    private void populateRoutes(final ExecutionContext executionContext,
                                final Map<String, StreamingHttpService> allRoutes,
                                final Map<String, RouteProvider> routes) {
        for (Map.Entry<String, RouteProvider> entry : routes.entrySet()) {
            final ServiceAdapterHolder adapterHolder = entry.getValue().buildRoute(executionContext);
            allRoutes.put(entry.getKey(), adapterHolder.serviceInvocationStrategy()
                    .offloadService(executionContext.executor(), adapterHolder.adaptor()));
        }
    }

    /**
     * A builder for building a {@link GrpcRouter}.
     */
    static final class Builder {

        private final Map<String, RouteProvider> routes;
        private final Map<String, RouteProvider> streamingRoutes;
        private final Map<String, RouteProvider> blockingRoutes;
        private final Map<String, RouteProvider> blockingStreamingRoutes;

        Builder() {
            routes = new HashMap<>();
            streamingRoutes = new HashMap<>();
            blockingRoutes = new HashMap<>();
            blockingStreamingRoutes = new HashMap<>();
        }

        Builder(final Map<String, RouteProvider> routes,
                final Map<String, RouteProvider> streamingRoutes,
                final Map<String, RouteProvider> blockingRoutes,
                final Map<String, RouteProvider> blockingStreamingRoutes) {
            this.routes = routes;
            this.streamingRoutes = streamingRoutes;
            this.blockingRoutes = blockingRoutes;
            this.blockingStreamingRoutes = blockingStreamingRoutes;
        }

        RouteProviders drainRoutes() {
            final Map<String, RouteProvider> allRoutes = new HashMap<>();
            allRoutes.putAll(routes);
            allRoutes.putAll(streamingRoutes);
            allRoutes.putAll(blockingRoutes);
            allRoutes.putAll(blockingStreamingRoutes);
            routes.clear();
            streamingRoutes.clear();
            blockingRoutes.clear();
            blockingStreamingRoutes.clear();
            return new RouteProviders(allRoutes);
        }

        static GrpcRouter.Builder merge(final GrpcRouter.Builder... builders) {
            final Map<String, RouteProvider> routes = new HashMap<>();
            final Map<String, RouteProvider> streamingRoutes = new HashMap<>();
            final Map<String, RouteProvider> blockingRoutes = new HashMap<>();
            final Map<String, RouteProvider> blockingStreamingRoutes = new HashMap<>();
            for (Builder builder : builders) {
                routes.putAll(builder.routes);
                streamingRoutes.putAll(builder.streamingRoutes);
                blockingRoutes.putAll(builder.blockingRoutes);
                blockingStreamingRoutes.putAll(builder.blockingStreamingRoutes);
            }
            return new Builder(routes, streamingRoutes, blockingRoutes, blockingStreamingRoutes);
        }

        <Req, Resp> Builder addRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final Route<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            routes.put(path, new RouteProvider(executionContext -> toStreamingHttpService(
                    (HttpService) (ctx, request, responseFactory) -> {
                        try {
                            final GrpcServiceContext serviceContext =
                                    new DefaultGrpcServiceContext(request.path(), ctx);
                            final HttpDeserializer<Req> deserializer =
                                    serializationProvider.deserializerFor(readGrpcMessageEncoding(request),
                                            requestClass);
                            return route.handle(serviceContext, request.payloadBody(deserializer))
                                    .map(rawResp -> newResponse(responseFactory,
                                            ctx.executionContext().bufferAllocator())
                                            .payloadBody(uncheckedCast(rawResp),
                                                    serializationProvider.serializerFor(serviceContext,
                                                            responseClass)))
                                    .recoverWith(cause -> succeeded(newErrorResponse(responseFactory, cause,
                                            ctx.executionContext().bufferAllocator())));
                        } catch (Throwable t) {
                            return succeeded(newErrorResponse(responseFactory, t,
                                    ctx.executionContext().bufferAllocator()));
                        }
                    }, strategy -> executionStrategy == null ? strategy : executionStrategy),
                    () -> toStreaming(route), () -> toRequestStreamingRoute(route),
                    () -> toResponseStreamingRoute(route), () -> route, route));
            return this;
        }

        <Req, Resp> Builder addStreamingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final StreamingRoute<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            streamingRoutes.put(path, new RouteProvider(executionContext -> {
                StreamingHttpService service = (ctx, request, responseFactory) -> {
                    try {
                        final GrpcServiceContext serviceContext = new DefaultGrpcServiceContext(request.path(), ctx);
                        final HttpDeserializer<Req> deserializer =
                                serializationProvider.deserializerFor(readGrpcMessageEncoding(request), requestClass);
                        final Publisher<Resp> response = route.handle(serviceContext, request.payloadBody(deserializer))
                                .map(GrpcUtils::uncheckedCast);
                        return succeeded(newResponse(responseFactory, response,
                                serializationProvider.serializerFor(serviceContext, responseClass),
                                ctx.executionContext().bufferAllocator()));
                    } catch (Throwable t) {
                        return succeeded(newErrorResponse(responseFactory, t,
                                ctx.executionContext().bufferAllocator()));
                    }
                };
                return new ServiceAdapterHolder() {
                    @Override
                    public StreamingHttpService adaptor() {
                        return service;
                    }

                    @Override
                    public HttpExecutionStrategy serviceInvocationStrategy() {
                        return executionStrategy == null ? noOffloadsStrategy() : executionStrategy;
                    }
                };
            }, () -> route, () -> toRequestStreamingRoute(route), () -> toResponseStreamingRoute(route),
                    () -> toRoute(route), route));
            return this;
        }

        <Req, Resp> Builder addRequestStreamingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final RequestStreamingRoute<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            return addStreamingRoute(path, executionStrategy,
                    (ctx, request) -> route.handle(ctx, request).toPublisher(), requestClass, responseClass,
                    serializationProvider);
        }

        /**
         * Adds a {@link ResponseStreamingRoute} to this builder.
         *
         * @param path for this route.
         * @param route {@link ResponseStreamingRoute} to add.
         * @param requestClass {@link Class} for the request object.
         * @param responseClass {@link Class} for the response object.
         * @param serializationProvider {@link GrpcSerializationProvider} for the route.
         * @param <Req> Type of request.
         * @param <Resp> Type of response.
         * @return {@code this}.
         */
        <Req, Resp> Builder addResponseStreamingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final ResponseStreamingRoute<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            return addStreamingRoute(path, executionStrategy, (ctx, request) -> request.firstOrError()
                    .flatMapPublisher(rawReq -> route.handle(ctx, uncheckedCast(rawReq))),
                    requestClass, responseClass, serializationProvider);
        }

        /**
         * Adds a {@link BlockingRoute} to this builder.
         *
         * @param path for this route.
         * @param route {@link BlockingRoute} to add.
         * @param requestClass {@link Class} for the request object.
         * @param responseClass {@link Class} for the response object.
         * @param serializationProvider {@link GrpcSerializationProvider} for the route.
         * @param <Req> Type of request.
         * @param <Resp> Type of response.
         * @return {@code this}.
         */
        <Req, Resp> Builder addBlockingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final BlockingRoute<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            blockingRoutes.put(path, new RouteProvider(executionContext ->
                    toStreamingHttpService((BlockingHttpService) (ctx, request, responseFactory) -> {
                        try {
                            final GrpcServiceContext serviceContext =
                                    new DefaultGrpcServiceContext(request.path(), ctx);
                            final HttpDeserializer<Req> deserializer =
                                    serializationProvider.deserializerFor(readGrpcMessageEncoding(request),
                                            requestClass);
                            final Resp response = route.handle(serviceContext, request.payloadBody(deserializer));
                            return newResponse(responseFactory, ctx.executionContext().bufferAllocator())
                                    .payloadBody(response,
                                            serializationProvider.serializerFor(serviceContext, responseClass));
                        } catch (Throwable t) {
                            return newErrorResponse(responseFactory, t, ctx.executionContext().bufferAllocator());
                        }
                    }, strategy -> executionStrategy == null ? strategy : executionStrategy),
                    () -> toStreaming(route), () -> toRequestStreamingRoute(route),
                    () -> toResponseStreamingRoute(route), () -> toRoute(route), route));
            return this;
        }

        /**
         * Adds a {@link BlockingStreamingRoute} to this builder.
         *
         * @param path for this route.
         * @param route {@link BlockingStreamingRoute} to add.
         * @param requestClass {@link Class} for the request object.
         * @param responseClass {@link Class} for the response object.
         * @param serializationProvider {@link GrpcSerializationProvider} for the route.
         * @param <Req> Type of request.
         * @param <Resp> Type of response.
         * @return {@code this}.
         */
        <Req, Resp> Builder addBlockingStreamingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final BlockingStreamingRoute<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            blockingRoutes.put(path, new RouteProvider(executionContext ->
                    toStreamingHttpService((ctx, request, response) -> {
                        final GrpcServiceContext serviceContext = new DefaultGrpcServiceContext(request.path(), ctx);
                        final HttpDeserializer<Req> deserializer =
                                serializationProvider.deserializerFor(readGrpcMessageEncoding(request), requestClass);
                        final HttpSerializer<Resp> serializer =
                                serializationProvider.serializerFor(serviceContext, responseClass);
                        final DefaultGrpcPayloadWriter<Resp> grpcPayloadWriter =
                                new DefaultGrpcPayloadWriter<>(response.sendMetaData(serializer));
                        try {
                            route.handle(serviceContext, request.payloadBody(deserializer), grpcPayloadWriter);
                        } catch (Throwable t) {
                            final HttpPayloadWriter<Resp> payloadWriter = grpcPayloadWriter.payloadWriter();
                            setStatus(payloadWriter.trailers(), t, ctx.executionContext().bufferAllocator());
                        } finally {
                            grpcPayloadWriter.close();
                        }
                    }, strategy -> executionStrategy == null ? strategy : executionStrategy), () -> toStreaming(route),
                    () -> toRequestStreamingRoute(route), () -> toResponseStreamingRoute(route),
                    () -> toRoute(route), route));
            return this;
        }

        /**
         * Adds a {@link RequestStreamingRoute} to this builder.
         *
         * @param path for this route.
         * @param route {@link RequestStreamingRoute} to add.
         * @param requestClass {@link Class} for the request object.
         * @param responseClass {@link Class} for the response object.
         * @param serializationProvider {@link GrpcSerializationProvider} for the route.
         * @param <Req> Type of request.
         * @param <Resp> Type of response.
         * @return {@code this}.
         */
        <Req, Resp> Builder addBlockingRequestStreamingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final BlockingRequestStreamingRoute<Req, Resp> route,
                final Class<Req> requestClass, final Class<Resp> responseClass,
                final GrpcSerializationProvider serializationProvider) {
            return addBlockingStreamingRoute(path, executionStrategy, (ctx, request, responseWriter) -> {
                        final Resp resp = route.handle(ctx, request);
                        responseWriter.write(resp);
                    },
                    requestClass, responseClass, serializationProvider);
        }

        /**
         * Adds a {@link ResponseStreamingRoute} to this builder.
         *
         * @param path for this route.
         * @param route {@link ResponseStreamingRoute} to add.
         * @param requestClass {@link Class} for the request object.
         * @param responseClass {@link Class} for the response object.
         * @param serializationProvider {@link GrpcSerializationProvider} for the route.
         * @param <Req> Type of request.
         * @param <Resp> Type of response.
         * @return {@code this}.
         */
        <Req, Resp> Builder addBlockingResponseStreamingRoute(
                final String path, @Nullable final GrpcExecutionStrategy executionStrategy,
                final BlockingResponseStreamingRoute<Req, Resp> route, final Class<Req> requestClass,
                final Class<Resp> responseClass, final GrpcSerializationProvider serializationProvider) {
            return addBlockingStreamingRoute(path, executionStrategy, (ctx, request, responseWriter) ->
                            route.handle(ctx, requireNonNull(request.iterator().next()), responseWriter),
                    requestClass, responseClass, serializationProvider);
        }

        /**
         * Builds a {@link GrpcRouter}.
         *
         * @return {@link GrpcRouter}.
         */
        public GrpcRouter build() {
            return new GrpcRouter(routes, streamingRoutes, blockingRoutes, blockingStreamingRoutes);
        }
    }

    private static final class DefaultGrpcPayloadWriter<Resp> implements GrpcPayloadWriter<Resp> {
        private final HttpPayloadWriter<Resp> payloadWriter;

        DefaultGrpcPayloadWriter(final HttpPayloadWriter<Resp> payloadWriter) {
            this.payloadWriter = payloadWriter;
        }

        @Override
        public void write(final Resp resp) throws IOException {
            payloadWriter.write(resp);
        }

        @Override
        public void close() throws IOException {
            payloadWriter.close();
        }

        @Override
        public void flush() throws IOException {
            payloadWriter.flush();
        }

        HttpPayloadWriter<Resp> payloadWriter() {
            return payloadWriter;
        }
    }

    static final class RouteProviders implements AsyncCloseable {

        private final Map<String, RouteProvider> routes;
        private final CompositeCloseable closeable;

        RouteProviders(final Map<String, RouteProvider> routes) {
            this.routes = routes;
            closeable = AsyncCloseables.newCompositeCloseable();
            for (RouteProvider provider : routes.values()) {
                closeable.append(provider);
            }
        }

        RouteProvider routeProvider(final String path) {
            final RouteProvider routeProvider = routes.get(path);
            if (routeProvider == null) {
                throw new IllegalArgumentException("No routes registered for path: " + path);
            }
            return routeProvider;
        }

        @Override
        public Completable closeAsync() {
            return closeable.closeAsync();
        }

        @Override
        public Completable closeAsyncGracefully() {
            return closeable.closeAsyncGracefully();
        }
    }

    static final class RouteProvider implements AsyncCloseable {

        private final Function<ExecutionContext, ServiceAdapterHolder> routeProvider;
        private final Supplier<StreamingRoute<?, ?>> toStreamingConverter;
        private final Supplier<RequestStreamingRoute<?, ?>> toRequestStreamingRouteConverter;
        private final Supplier<ResponseStreamingRoute<?, ?>> toResponseStreamingRouteConverter;
        private final Supplier<Route<?, ?>> toRouteConverter;
        private final AsyncCloseable closeable;

        RouteProvider(final Function<ExecutionContext, ServiceAdapterHolder> routeProvider,
                      final Supplier<StreamingRoute<?, ?>> toStreamingConverter,
                      final Supplier<RequestStreamingRoute<?, ?>> toRequestStreamingRouteConverter,
                      final Supplier<ResponseStreamingRoute<?, ?>> toResponseStreamingRouteConverter,
                      final Supplier<Route<?, ?>> toRouteConverter,
                      final AsyncCloseable closeable) {
            this.routeProvider = routeProvider;
            this.toStreamingConverter = toStreamingConverter;
            this.toRequestStreamingRouteConverter = toRequestStreamingRouteConverter;
            this.toResponseStreamingRouteConverter = toResponseStreamingRouteConverter;
            this.toRouteConverter = toRouteConverter;
            this.closeable = closeable;
        }

        RouteProvider(final Function<ExecutionContext, ServiceAdapterHolder> routeProvider,
                      final Supplier<StreamingRoute<?, ?>> toStreamingConverter,
                      final Supplier<RequestStreamingRoute<?, ?>> toRequestStreamingRouteConverter,
                      final Supplier<ResponseStreamingRoute<?, ?>> toResponseStreamingRouteConverter,
                      final Supplier<Route<?, ?>> toRouteConverter,
                      final GracefulAutoCloseable closeable) {
            this(routeProvider, toStreamingConverter, toRequestStreamingRouteConverter,
                    toResponseStreamingRouteConverter, toRouteConverter, toAsyncCloseable(closeable));
        }

        ServiceAdapterHolder buildRoute(ExecutionContext executionContext) {
            return routeProvider.apply(executionContext);
        }

        <Req, Resp> RequestStreamingRoute<Req, Resp> asRequestStreamingRoute() {
            // We assume that generated code passes the correct types here.
            @SuppressWarnings("unchecked")
            RequestStreamingRoute<Req, Resp> toReturn =
                    (RequestStreamingRoute<Req, Resp>) toRequestStreamingRouteConverter.get();
            return toReturn;
        }

        <Req, Resp> ResponseStreamingRoute<Req, Resp>
        asResponseStreamingRoute() {
            // We assume that generated code passes the correct types here.
            @SuppressWarnings("unchecked")
            ResponseStreamingRoute<Req, Resp> toReturn =
                    (ResponseStreamingRoute<Req, Resp>) toResponseStreamingRouteConverter.get();
            return toReturn;
        }

        <Req, Resp> StreamingRoute<Req, Resp> asStreamingRoute() {
            // We assume that generated code passes the correct types here.
            @SuppressWarnings("unchecked")
            StreamingRoute<Req, Resp> toReturn = (StreamingRoute<Req, Resp>) toStreamingConverter.get();
            return toReturn;
        }

        <Req, Resp> Route<Req, Resp> asRoute() {
            // We assume that generated code passes the correct types here.
            @SuppressWarnings("unchecked")
            Route<Req, Resp> toReturn = (Route<Req, Resp>) toRouteConverter.get();
            return toReturn;
        }

        @Override
        public Completable closeAsync() {
            return closeable.closeAsync();
        }

        @Override
        public Completable closeAsyncGracefully() {
            return closeable.closeAsyncGracefully();
        }
    }
}
