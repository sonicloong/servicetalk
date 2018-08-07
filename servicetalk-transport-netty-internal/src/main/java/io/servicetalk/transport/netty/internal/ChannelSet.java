/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.transport.netty.internal;

import io.servicetalk.concurrent.api.AsyncCloseable;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.CompletableProcessor;
import io.servicetalk.concurrent.api.CompositeCloseable;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.ListenableAsyncCloseable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.servicetalk.concurrent.api.AsyncCloseables.newCompositeCloseable;
import static io.servicetalk.concurrent.api.Executors.immediate;
import static io.servicetalk.transport.netty.internal.CloseStates.CLOSING;
import static io.servicetalk.transport.netty.internal.CloseStates.GRACEFULLY_CLOSING;
import static io.servicetalk.transport.netty.internal.CloseStates.OPEN;
import static java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater;

/**
 * Manages a set of {@link Channel}s to provide a mechanism for closing all of them.
 * <p>
 * Channels are removed from the set when they are closed.
 */
public final class ChannelSet implements ListenableAsyncCloseable {
    private static final AtomicIntegerFieldUpdater<ChannelSet> stateUpdater = newUpdater(ChannelSet.class, "state");

    private final ChannelFutureListener remover = new ChannelFutureListener() {
        @Override
        public void operationComplete(final ChannelFuture future) {
            final boolean wasRemoved = channelMap.remove(future.channel().id()) != null;
            if (wasRemoved && state != OPEN && channelMap.isEmpty()) {
                onCloseProcessor.onComplete();
            }
        }
    };

    private final Map<ChannelId, Channel> channelMap = new ConcurrentHashMap<>();
    private final CompletableProcessor onCloseProcessor = new CompletableProcessor();
    private final Completable onClose;
    @SuppressWarnings("unused")
    private volatile int state;

    /**
     * New instance.
     *
     * @param offloadingExecutor {@link Executor} to use for offloading close signals.
     */
    public ChannelSet(Executor offloadingExecutor) {
        onClose = onCloseProcessor.publishOn(offloadingExecutor);
    }

    /**
     * Add a {@link Channel} to this {@link ChannelSet}, if it is not already present. {@link Channel#id()} is used to
     * check uniqueness.
     *
     * @param channel The {@link Channel} to add.
     * @return {@code true} if the channel was added successfully, {@code false} otherwise.
     */
    public boolean addIfAbsent(final Channel channel) {
        final boolean added = channelMap.putIfAbsent(channel.id(), channel) == null;

        if (state != OPEN) {
            if (added) {
                channelMap.remove(channel.id(), channel);
                channel.close();
            }
        } else if (added) {
            channel.closeFuture().addListener(remover);
        }

        return added;
    }

    @Override
    public Completable closeAsync() {
        return new Completable() {
            @Override
            protected void handleSubscribe(final Subscriber subscriber) {
                onClose.subscribe(subscriber);
                if (stateUpdater.getAndSet(ChannelSet.this, CLOSING) == CLOSING) {
                    return;
                }

                if (channelMap.isEmpty()) {
                    onCloseProcessor.onComplete();
                    return;
                }

                for (final Channel channel : channelMap.values()) {
                    // We don't try to catch exceptions here because we're only invoking Netty or ServiceTalk code, no
                    // user-provided code.
                    channel.close();
                }
            }
        };
    }

    @Override
    public Completable closeAsyncGracefully() {
        return new Completable() {
            @Override
            protected void handleSubscribe(final Subscriber subscriber) {
                if (!stateUpdater.compareAndSet(ChannelSet.this, OPEN, GRACEFULLY_CLOSING)) {
                    onClose.subscribe(subscriber);
                    return;
                }

                if (channelMap.isEmpty()) {
                    onClose.subscribe(subscriber);
                    onCloseProcessor.onComplete();
                    return;
                }

                CompositeCloseable closeable = newCompositeCloseable().appendAll(() -> onClose);

                for (final Channel channel : channelMap.values()) {
                    final ConnectionHolderChannelHandler<?, ?> holder =
                            channel.pipeline().get(ConnectionHolderChannelHandler.class);
                    Connection<?, ?> connection = holder == null ? null : holder.getConnection();
                    if (connection != null) {
                        // Upon shutdown of the set, we will close all live channels. If close of individual channels
                        // are offloaded, then this would trigger a surge in threads required to offload these closures.
                        // Here we assume that if there is any offloading required, it is done by offloading the
                        // Completable returned by closeAsyncGracefully() hence offloading each channel is not required.
                        // Hence, we override the offloading on each channel for this particular subscribe to use
                        // immediate and effectively disable offloading on each channel.
                        closeable.merge(new AsyncCloseable() {
                            @Override
                            public Completable closeAsync() {
                                return connection.closeAsync().publishOnOverride(immediate());
                            }

                            @Override
                            public Completable closeAsyncGracefully() {
                                return connection.closeAsyncGracefully().publishOnOverride(immediate());
                            }
                        });
                    } else {
                        channel.close();
                    }
                }

                closeable.closeAsyncGracefully().subscribe(subscriber);
            }
        };
    }

    @Override
    public Completable onClose() {
        return onClose;
    }
}
