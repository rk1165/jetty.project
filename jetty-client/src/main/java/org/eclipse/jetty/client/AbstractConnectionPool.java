//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

import static java.util.stream.Collectors.toCollection;

@ManagedObject
public abstract class AbstractConnectionPool extends ContainerLifeCycle implements ConnectionPool, Dumpable, Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(AbstractConnectionPool.class);

    private final HttpDestination destination;
    private final Callback requester;
    private final Pool<ConnectionHolder> pool;
    private boolean maximizeConnections;
    private volatile long maxDuration = 0L;

    public static class ConnectionHolder
    {
        private final Connection connection;
        private final long creationTimestamp;

        ConnectionHolder(Connection connection)
        {
            this.connection = connection;
            this.creationTimestamp = System.nanoTime();
        }

        boolean isExpired(long timeoutNanos)
        {
            return System.nanoTime() - creationTimestamp >= timeoutNanos;
        }
    }

    /**
     * @deprecated use {@link #AbstractConnectionPool(HttpDestination, int, boolean, Callback)} instead
     */
    @Deprecated
    protected AbstractConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        this((HttpDestination)destination, maxConnections, false, requester);
    }

    protected AbstractConnectionPool(HttpDestination destination, int maxConnections, boolean cache, Callback requester)
    {
        this(destination, new Pool<>(Pool.StrategyType.FIRST, maxConnections, cache), requester);
    }

    protected AbstractConnectionPool(HttpDestination destination, Pool<ConnectionHolder> pool, Callback requester)
    {
        this.destination = destination;
        this.requester = requester;
        this.pool = pool;
        addBean(pool);
    }

    @Override
    protected void doStop() throws Exception
    {
        pool.close();
    }

    @Override
    public CompletableFuture<Void> preCreateConnections(int connectionCount)
    {
        CompletableFuture<?>[] futures = new CompletableFuture[connectionCount];
        for (int i = 0; i < connectionCount; i++)
        {
            futures[i] = tryCreateAsync(getMaxConnectionCount());
        }
        return CompletableFuture.allOf(futures);
    }

    @ManagedAttribute(value = "The maximum duration in milliseconds a connection can be used for before it gets closed")
    public long getMaxDuration()
    {
        return maxDuration;
    }

    public void setMaxDuration(long maxDuration)
    {
        this.maxDuration = maxDuration;
    }

    protected int getMaxMultiplex()
    {
        return pool.getMaxMultiplex();
    }

    protected void setMaxMultiplex(int maxMultiplex)
    {
        pool.setMaxMultiplex(maxMultiplex);
    }

    protected int getMaxUsageCount()
    {
        return pool.getMaxUsageCount();
    }

    protected void setMaxUsageCount(int maxUsageCount)
    {
        pool.setMaxUsageCount(maxUsageCount);
    }

    @ManagedAttribute(value = "The number of active connections", readonly = true)
    public int getActiveConnectionCount()
    {
        return pool.getInUseCount();
    }

    @ManagedAttribute(value = "The number of idle connections", readonly = true)
    public int getIdleConnectionCount()
    {
        return pool.getIdleCount();
    }

    @ManagedAttribute(value = "The max number of connections", readonly = true)
    public int getMaxConnectionCount()
    {
        return pool.getMaxEntries();
    }

    @ManagedAttribute(value = "The number of connections", readonly = true)
    public int getConnectionCount()
    {
        return pool.size();
    }

    /**
     * @return the number of pending connections
     * @deprecated use {@link #getPendingConnectionCount()} instead
     */
    @ManagedAttribute(value = "The number of pending connections", readonly = true)
    @Deprecated
    public int getPendingCount()
    {
        return getPendingConnectionCount();
    }

    @ManagedAttribute(value = "The number of pending connections", readonly = true)
    public int getPendingConnectionCount()
    {
        return pool.getReservedCount();
    }

    @Override
    public boolean isEmpty()
    {
        return pool.size() == 0;
    }

    @Override
    @ManagedAttribute("Whether this pool is closed")
    public boolean isClosed()
    {
        return pool.isClosed();
    }

    @ManagedAttribute("Whether the pool tries to maximize the number of connections used")
    public boolean isMaximizeConnections()
    {
        return maximizeConnections;
    }

    /**
     * <p>Sets whether the number of connections should be maximized.</p>
     *
     * @param maximizeConnections whether the number of connections should be maximized
     */
    public void setMaximizeConnections(boolean maximizeConnections)
    {
        this.maximizeConnections = maximizeConnections;
    }

    @Override
    public Connection acquire()
    {
        return acquire(true);
    }

    /**
     * <p>Returns an idle connection, if available;
     * if an idle connection is not available, and the given {@code create} parameter is {@code true}
     * or {@link #isMaximizeConnections()} is {@code true},
     * then schedules the opening of a new connection, if possible within the configuration of this
     * connection pool (for example, if it does not exceed the max connection count);
     * otherwise returns {@code null}.</p>
     *
     * @param create whether to schedule the opening of a connection if no idle connections are available
     * @return an idle connection or {@code null} if no idle connections are available
     * @see #tryCreate(int)
     */
    protected Connection acquire(boolean create)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Acquiring create={} on {}", create, this);
        Connection connection = activate();
        if (connection == null && (create || isMaximizeConnections()))
        {
            tryCreate(destination.getQueuedRequestCount());
            connection = activate();
        }
        return connection;
    }

    /**
     * <p>Schedules the opening of a new connection.</p>
     * <p>Whether a new connection is scheduled for opening is determined by the {@code maxPending} parameter:
     * if {@code maxPending} is greater than the current number of connections scheduled for opening,
     * then this method returns without scheduling the opening of a new connection;
     * if {@code maxPending} is negative, a new connection is always scheduled for opening.</p>
     *
     * @param maxPending the max desired number of connections scheduled for opening,
     * or a negative number to always trigger the opening of a new connection
     */
    protected void tryCreate(int maxPending)
    {
        tryCreateAsync(maxPending);
    }

    private CompletableFuture<Void> tryCreateAsync(int maxPending)
    {
        int connectionCount = getConnectionCount();
        if (LOG.isDebugEnabled())
            LOG.debug("Try creating connection {}/{} with {}/{} pending", connectionCount, getMaxConnectionCount(), getPendingConnectionCount(), maxPending);

        Pool<ConnectionHolder>.Entry entry = pool.reserve(maxPending);
        if (entry == null)
            return CompletableFuture.completedFuture(null);

        if (LOG.isDebugEnabled())
            LOG.debug("Creating connection {}/{}", connectionCount, getMaxConnectionCount());

        CompletableFuture<Void> future = new CompletableFuture<>();
        destination.newConnection(new Promise<Connection>()
        {
            @Override
            public void succeeded(Connection connection)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Connection {}/{} creation succeeded {}", connectionCount, getMaxConnectionCount(), connection);
                if (!(connection instanceof Attachable))
                {
                    failed(new IllegalArgumentException("Invalid connection object: " + connection));
                    return;
                }
                ((Attachable)connection).setAttachment(entry);
                onCreated(connection);
                entry.enable(new ConnectionHolder(connection), false);
                idle(connection, false);
                future.complete(null);
                proceed();
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Connection {}/{} creation failed", connectionCount, getMaxConnectionCount(), x);
                entry.remove();
                future.completeExceptionally(x);
                requester.failed(x);
            }
        });

        return future;
    }

    protected void proceed()
    {
        requester.succeeded();
    }

    protected Connection activate()
    {
        while (true)
        {
            Pool<ConnectionHolder>.Entry entry = pool.acquire();
            if (entry == null)
                return null;
            Connection connection = entry.getPooled().connection;

            if (maxDuration > 0L && entry.getPooled().isExpired(maxDuration))
            {
                if (remove(connection))
                    IO.close(connection);
                continue;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Activated {} {}", entry, pool);
            acquired(connection);
            return connection;
        }
    }

    @Override
    public boolean isActive(Connection connection)
    {
        if (!(connection instanceof Attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Attachable attachable = (Attachable)connection;
        @SuppressWarnings("unchecked")
        Pool<ConnectionHolder>.Entry entry = (Pool<ConnectionHolder>.Entry)attachable.getAttachment();
        if (entry == null)
            return false;
        return !entry.isIdle();
    }

    @Override
    public boolean release(Connection connection)
    {
        if (!deactivate(connection))
            return false;
        released(connection);
        return idle(connection, isClosed());
    }

    protected boolean deactivate(Connection connection)
    {
        if (!(connection instanceof Attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Attachable attachable = (Attachable)connection;
        @SuppressWarnings("unchecked")
        Pool<ConnectionHolder>.Entry entry = (Pool<ConnectionHolder>.Entry)attachable.getAttachment();
        if (entry == null)
            return true;
        boolean reusable = pool.release(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("Released ({}) {} {}", reusable, entry, pool);
        if (reusable)
            return true;
        remove(connection);
        return false;
    }

    @Override
    public boolean remove(Connection connection)
    {
        return remove(connection, false);
    }

    protected boolean remove(Connection connection, boolean force)
    {
        if (!(connection instanceof Attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Attachable attachable = (Attachable)connection;
        @SuppressWarnings("unchecked")
        Pool<ConnectionHolder>.Entry entry = (Pool<ConnectionHolder>.Entry)attachable.getAttachment();
        if (entry == null)
            return false;
        boolean removed = pool.remove(entry);
        if (removed)
            attachable.setAttachment(null);
        if (LOG.isDebugEnabled())
            LOG.debug("Removed ({}) {} {}", removed, entry, pool);
        if (removed || force)
        {
            released(connection);
            removed(connection);
        }
        return removed;
    }

    protected void onCreated(Connection connection)
    {
    }

    protected boolean idle(Connection connection, boolean close)
    {
        return !close;
    }

    protected void acquired(Connection connection)
    {
    }

    protected void released(Connection connection)
    {
    }

    protected void removed(Connection connection)
    {
    }

    /**
     * @return an unmodifiable queue working as a view of the idle connections.
     * @deprecated Relying on this method indicates a reliance on the implementation details.
     */
    @Deprecated
    public Queue<Connection> getIdleConnections()
    {
        return pool.values().stream()
            .filter(Pool.Entry::isIdle)
            .filter(entry -> !entry.isClosed())
            .map(entry -> entry.getPooled().connection)
            .collect(toCollection(ArrayDeque::new));
    }

    /**
     * @return an unmodifiable collection working as a view of the active connections.
     * @deprecated Relying on this method indicates a reliance on the implementation details.
     */
    @Deprecated
    public Collection<Connection> getActiveConnections()
    {
        return pool.values().stream()
            .filter(entry -> !entry.isIdle())
            .filter(entry -> !entry.isClosed())
            .map(entry -> entry.getPooled().connection)
            .collect(Collectors.toList());
    }

    @Override
    public void close()
    {
        pool.close();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this);
    }

    @Override
    public boolean sweep()
    {
        pool.values().stream().filter(entry -> entry.getPooled() instanceof Sweeper.Sweepable).forEach(entry ->
        {
            ConnectionHolder holder = entry.getPooled();
            if (((Sweeper.Sweepable)holder.connection).sweep())
            {
                boolean removed = remove(holder.connection);
                LOG.warn("Connection swept: {}{}{} from active connections{}{}",
                    holder.connection,
                    System.lineSeparator(),
                    removed ? "Removed" : "Not removed",
                    System.lineSeparator(),
                    dump());
            }
        });
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[c=%d/%d/%d,a=%d,i=%d]",
            getClass().getSimpleName(),
            hashCode(),
            getPendingConnectionCount(),
            getConnectionCount(),
            getMaxConnectionCount(),
            getActiveConnectionCount(),
            getIdleConnectionCount());
    }
}
