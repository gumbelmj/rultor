/**
 * Copyright (c) 2009-2013, rultor.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the rultor.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rultor.conveyer;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.jcabi.aspects.Loggable;
import com.jcabi.aspects.Tv;
import com.jcabi.log.VerboseThreads;
import com.rultor.spi.Conveyer;
import com.rultor.spi.Conveyer.Log;
import com.rultor.spi.Queue;
import com.rultor.spi.Repo;
import com.rultor.spi.State;
import com.rultor.spi.Users;
import com.rultor.spi.Work;
import java.io.Closeable;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

/**
 * Horizontally scalable execution conveyer.
 *
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @since 1.0
 */
@Loggable(Loggable.INFO)
@ToString
@EqualsAndHashCode(of = { "queue", "repo" })
@SuppressWarnings("PMD.DoNotUseThreads")
public final class SimpleConveyer
    implements Conveyer, Closeable, Callable<Void> {

    /**
     * In how many threads we run instances.
     */
    private static final int THREADS =
        Runtime.getRuntime().availableProcessors() * Tv.TEN;

    /**
     * Queue.
     */
    private final transient Queue queue;

    /**
     * Repository.
     */
    private final transient Repo repo;

    /**
     * Users.
     */
    private final transient Users users;

    /**
     * State to use for everybody.
     */
    private final transient State state = new State.Memory();

    /**
     * Log appender.
     */
    private final transient ConveyerAppender appender;

    /**
     * Counter of executed jobs.
     */
    private transient Counter counter;

    /**
     * Consumer of new specs from Queue.
     */
    private final transient ExecutorService consumer =
        Executors.newSingleThreadExecutor(
            new VerboseThreads(SimpleConveyer.class)
        );

    /**
     * Executor of instances.
     */
    private final transient ExecutorService executor =
        Executors.newCachedThreadPool(
            new VerboseThreads(SimpleConveyer.class)
        );

    /**
     * Public ctor.
     * @param que The queue of specs
     * @param rep Repo
     * @param usrs Users
     * @param log Log
     * @checkstyle ParameterNumber (4 lines)
     */
    public SimpleConveyer(final Queue que, final Repo rep, final Users usrs,
        final Log log) {
        this.queue = que;
        this.repo = rep;
        this.users = usrs;
        this.appender = new ConveyerAppender(log);
        this.appender.setThreshold(Level.DEBUG);
        this.appender.setLayout(new PatternLayout("%m"));
        Logger.getRootLogger().addAppender(this.appender);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        this.consumer.submit(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        Logger.getRootLogger().removeAppender(this.appender);
        this.appender.close();
        this.consumer.shutdown();
        this.executor.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Loggable(value = Loggable.INFO, limit = Integer.MAX_VALUE)
    public Void call() throws Exception {
        while (true) {
            this.submit(this.queue.pull());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(final MetricRegistry registry) {
        this.counter = registry.counter(
            MetricRegistry.name(this.getClass(), "done-jobs")
        );
    }

    /**
     * Submit work for execution in the threaded executor.
     * @param work Work
     */
    private void submit(final Work work) {
        this.executor.submit(
            new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    new LoggedInstance(
                        SimpleConveyer.this.repo,
                        SimpleConveyer.this.users.fetch(work.owner()),
                        SimpleConveyer.this.appender
                    ).pulse(work, SimpleConveyer.this.substate(work));
                    SimpleConveyer.this.counter.inc();
                    return null;
                }
            }
        );
    }

    /**
     * Create sub-state.
     * @param work Work
     * @return Steate for this particular work
     */
    private State substate(final Work work) {
        // @checkstyle AnonInnerLength (50 lines)
        return new State() {
            @Override
            public String get(final String key) {
                return SimpleConveyer.this.state.get(this.prefixed(key));
            }
            @Override
            public boolean checkAndSet(final String key, final String value) {
                return SimpleConveyer.this.state.checkAndSet(
                    this.prefixed(key), value
                );
            }
            @Override
            public boolean has(final String key) {
                return SimpleConveyer.this.state.has(this.prefixed(key));
            }
            private String prefixed(final String key) {
                return String.format(
                    "%s/%s/%s", work.owner(), work.unit(), key
                );
            }
        };
    }

}
