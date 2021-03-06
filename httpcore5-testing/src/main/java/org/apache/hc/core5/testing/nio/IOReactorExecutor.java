/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.core5.testing.nio;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.http.ExceptionListener;
import org.apache.hc.core5.http.nio.command.ShutdownCommand;
import org.apache.hc.core5.io.ShutdownType;
import org.apache.hc.core5.reactor.AbstractMultiworkerIOReactor;
import org.apache.hc.core5.reactor.ExceptionEvent;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Asserts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

abstract class IOReactorExecutor<T extends AbstractMultiworkerIOReactor> implements AutoCloseable {

    enum Status { READY, RUNNING, TERMINATED }

    private final Logger log = LogManager.getLogger(Http1TestClient.class);

    private final IOReactorConfig ioReactorConfig;
    private final ExceptionListener exceptionListener;
    private final ExecutorService executorService;
    private final ThreadFactory workerThreadFactory;
    private final AtomicReference<T> ioReactorRef;
    private final AtomicReference<Status> status;

    IOReactorExecutor(
            final IOReactorConfig ioReactorConfig,
            final ThreadFactory threadFactory,
            final ThreadFactory workerThreadFactory) {
        super();
        this.ioReactorConfig = ioReactorConfig != null ? ioReactorConfig : IOReactorConfig.DEFAULT;
        this.exceptionListener = new ExceptionListener() {

            @Override
            public void onError(final Exception ex) {
                log.error(ex.getMessage(), ex);
            }

        };
        this.executorService = Executors.newSingleThreadExecutor(threadFactory);
        this.workerThreadFactory = workerThreadFactory;
        this.ioReactorRef = new AtomicReference<>(null);
        this.status = new AtomicReference<>(Status.READY);
    }

    abstract T createIOReactor(
            IOEventHandlerFactory ioEventHandlerFactory,
            IOReactorConfig ioReactorConfig,
            ThreadFactory threadFactory,
            Callback<IOSession> sessionShutdownCallback) throws IOException;

    protected void execute(final IOEventHandlerFactory ioEventHandlerFactory) throws IOException {
        Args.notNull(ioEventHandlerFactory, "Handler factory");
        if (ioReactorRef.compareAndSet(null, createIOReactor(
                ioEventHandlerFactory,
                ioReactorConfig,
                workerThreadFactory != null ? workerThreadFactory : new DefaultThreadFactory("i/o dispatch"),
                new Callback<IOSession>() {

                    @Override
                    public void execute(final IOSession session) {
                        session.addFirst(new ShutdownCommand(ShutdownType.GRACEFUL));
                    }

                }))) {
            if (status.compareAndSet(Status.READY, Status.RUNNING)) {
                executorService.execute(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            ioReactorRef.get().execute();
                        } catch (final Exception ex) {
                            if (exceptionListener != null) {
                                exceptionListener.onError(ex);
                            }
                        }
                    }
                });
            }
        } else {
            throw new IllegalStateException("I/O reactor has already been started");
        }
    }

    private T ensureRunning() {
        final T ioReactor = ioReactorRef.get();
        Asserts.check(ioReactor != null, "I/O reactor has not been started");
        return ioReactor;
    }

    T reactor() {
        return ensureRunning();
    }

    public IOReactorStatus getStatus() {
        final T ioReactor = ioReactorRef.get();
        return ioReactor != null ? ioReactor.getStatus() : IOReactorStatus.INACTIVE;
    }

    public List<ExceptionEvent> getAuditLog() {
        final T ioReactor = ensureRunning();
        return ioReactor.getAuditLog();
    }

    public void awaitShutdown(final TimeValue waitTime) throws InterruptedException {
        Args.notNull(waitTime, "Wait time");
        final T ioReactor = ioReactorRef.get();
        if (ioReactor != null) {
            ioReactor.awaitShutdown(waitTime);
        }
    }

    public void initiateShutdown() {
        final T ioReactor = ioReactorRef.get();
        if (ioReactor != null) {
            if (status.compareAndSet(Status.RUNNING, Status.TERMINATED)) {
                ioReactor.initiateShutdown();
            }
        }
    }

    public void shutdown(final TimeValue graceTime) {
        Args.notNull(graceTime, "Grace time");
        final T ioReactor = ioReactorRef.get();
        if (ioReactor != null) {
            if (status.compareAndSet(Status.RUNNING, Status.TERMINATED)) {
                ioReactor.initiateShutdown();
            }
            try {
                ioReactor.awaitShutdown(graceTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ioReactor.shutdown(ShutdownType.IMMEDIATE);
        }
    }

    @Override
    public void close() throws Exception {
        shutdown(TimeValue.ofSeconds(5));
    }

}
