/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.coordinator.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.apache.felix.jmx.service.coordinator.CoordinatorMBean;
import org.osgi.framework.Bundle;
import org.osgi.service.coordinator.Coordination;
import org.osgi.service.coordinator.CoordinationException;
import org.osgi.service.coordinator.Participant;

/**
 * The <code>CoordinationMgr</code> is the actual back-end manager of all
 * Coordinations created by the Coordinator implementation. The methods in this
 * class fall into three categories:
 * <ul>
 * <li>Actual implementations of the Coordinator interface on behalf of the
 * per-bundle Coordinator service instances</li>
 * <li>Implementation of the CoordinatorMBean interface allowing JMX management
 * of the coordinations</li>
 * <li>Management support to timeout and cleanup coordinations</li>
 * </ul>
 */
public class CoordinationMgr implements CoordinatorMBean
{

    private ThreadLocal<Stack<Coordination>> threadStacks;

    private final AtomicLong ctr;

    private final Map<Long, CoordinationImpl> coordinations;

    private final Map<Participant, CoordinationImpl> participants;

    private final Timer coordinationTimer;

    /**
     * Default coordination timeout. Currently hard coded to be 30s (the
     * specified minimum timeout). Should be made configurable, but not less
     * than 30s.
     */
    private long defaultTimeOut = 30 * 1000L;

    /**
     * Wait at most 60 seconds for participant to be eligible for participation
     * in a coordination.
     *
     * @see #singularizeParticipant(Participant, CoordinationImpl)
     */
    private long participationTimeOut = 60 * 1000L;

    CoordinationMgr()
    {
        threadStacks = new ThreadLocal<Stack<Coordination>>();
        ctr = new AtomicLong(-1);
        coordinations = new HashMap<Long, CoordinationImpl>();
        participants = new IdentityHashMap<Participant, CoordinationImpl>();
        coordinationTimer = new Timer("Coordination Timer", true);
    }

    void cleanUp()
    {
        // terminate coordination timeout timer
        coordinationTimer.purge();
        coordinationTimer.cancel();

        // terminate all active coordinations
        final List<Coordination> coords = new ArrayList<Coordination>();
        synchronized ( this.coordinations ) {
            coords.addAll(this.coordinations.values());
            this.coordinations.clear();
        }
        for(final Coordination c : coords)
        {
            if ( !c.isTerminated() )
            {
                c.fail(Coordination.RELEASED);
            }
        }

        // release all participants
        synchronized ( this.participants )
        {
            participants.clear();
        }

        // cannot really clear out the thread local but we can let it go
        threadStacks = null;
    }

    void configure(final long coordinationTimeout, final long participationTimeout)
    {
        this.defaultTimeOut = coordinationTimeout;
        this.participationTimeOut = participationTimeout;
    }

    void schedule(final TimerTask task, final long deadLine)
    {
        if (deadLine < 0)
        {
            task.cancel();
        }
        else
        {
            coordinationTimer.schedule(task, new Date(deadLine));
        }
    }

    void lockParticipant(final Participant p, final CoordinationImpl c)
    {
        synchronized (participants)
        {
            // wait for participant to be released
            long cutOff = System.currentTimeMillis() + participationTimeOut;
            long waitTime = (participationTimeOut > 500) ? participationTimeOut / 500 : participationTimeOut;
            // TODO - the above wait time looks wrong e.g. if it's 800, the wait time 1ms
            CoordinationImpl current = participants.get(p);
            while (current != null && current != c)
            {
                if (current.getThread() != null && current.getThread() == c.getThread())
                {
                    throw new CoordinationException("Participant " + p + " already participating in Coordination "
                        + current.getId() + "/" + current.getName() + " in this thread", c,
                        CoordinationException.DEADLOCK_DETECTED);
                }

                try
                {
                    participants.wait(waitTime);
                }
                catch (InterruptedException ie)
                {
                    throw new CoordinationException("Interrupted waiting to add Participant " + p
                        + " currently participating in Coordination " + current.getId() + "/" + current.getName()
                        + " in this thread", c, CoordinationException.LOCK_INTERRUPTED);
                }

                // timeout waiting for participation
                if (System.currentTimeMillis() > cutOff)
                {
                    throw new CoordinationException("Timed out waiting to join coordinaton", c,
                        CoordinationException.UNKNOWN);
                }

                // check again
                current = participants.get(p);
            }

            // lock participant into coordination
            participants.put(p, c);
        }
    }

    void releaseParticipant(final Participant p)
    {
        synchronized (participants)
        {
            participants.remove(p);
            participants.notifyAll();
        }
    }

    // ---------- Coordinator back end implementation

    Coordination create(final CoordinatorImpl owner, final String name, final long timeout)
    {
        final long id = ctr.incrementAndGet();
        final CoordinationImpl c = new CoordinationImpl(owner, id, name, timeout);
        synchronized ( this.coordinations )
        {
            coordinations.put(id, c);
        }
        return c;
    }

    void unregister(final CoordinationImpl c, final boolean removeFromThread)
    {
        synchronized ( this.coordinations )
        {
            coordinations.remove(c.getId());
        }
        if ( removeFromThread )
        {
            Stack<Coordination> stack = threadStacks.get();
            if (stack != null)
            {
                stack.remove(c);
            }
        }
    }

    void push(final CoordinationImpl c)
    {
        Stack<Coordination> stack = threadStacks.get();
        if (stack == null)
        {
            stack = new Stack<Coordination>();
            threadStacks.set(stack);
        }
        else
        {
            if ( stack.contains(c) )
            {
                throw new CoordinationException("Coordination already pushed", c, CoordinationException.ALREADY_PUSHED);
            }
        }

        c.setAssociatedThread(Thread.currentThread());
        stack.push(c);
    }

    Coordination pop()
    {
        Stack<Coordination> stack = threadStacks.get();
        if (stack != null && !stack.isEmpty())
        {
            final CoordinationImpl c = (CoordinationImpl)stack.pop();
            if ( c != null ) {
                c.setAssociatedThread(null);
            }
            return c;
        }
        return null;
    }

    Coordination peek()
    {
        Stack<Coordination> stack = threadStacks.get();
        if (stack != null && !stack.isEmpty())
        {
            return stack.peek();
        }
        return null;
    }

    Collection<Coordination> getCoordinations()
    {
        final ArrayList<Coordination> result = new ArrayList<Coordination>();
        synchronized ( this.coordinations )
        {
            result.addAll(this.coordinations.values());
        }
        return result;
    }

    Coordination getCoordinationById(final long id)
    {
        synchronized ( this.coordinations )
        {
            final CoordinationImpl c = coordinations.get(id);
            return (c == null || c.isTerminated()) ? null : c;
        }
    }

    // ---------- CoordinatorMBean interface

    public TabularData listCoordinations(String regexFilter)
    {
        return null;
/*
        Pattern p = Pattern.compile(regexFilter);
        TabularData td = new TabularDataSupport(COORDINATIONS_TYPE);
        for (CoordinationImpl c : coordinations.values())
        {
            if (p.matcher(c.getName()).matches())
            {
                try
                {
                    td.put(fromCoordination(c));
                }
                catch (OpenDataException e)
                {
                    // TODO: log
                }
            }
        }
        return td;
*/
    }

    public CompositeData getCoordination(long id) throws IOException
    {
        return null;
        /*
        Coordination c = getCoordinationById(id);
        if (c != null)
        {
            try
            {
                return fromCoordination((CoordinationImpl) c);
            }
            catch (OpenDataException e)
            {
                throw new IOException(e.toString());
            }
        }
        throw new IOException("No such Coordination " + id);
        */
    }

    public boolean fail(long id, String reason)
    {
        Coordination c = getCoordinationById(id);
        if (c != null)
        {
            return c.fail(new Exception(reason));
        }
        return false;
    }

    public void addTimeout(long id, long timeout)
    {
        Coordination c = getCoordinationById(id);
        if (c != null)
        {
            c.extendTimeout(timeout);
        }
    }
/*
    private CompositeData fromCoordination(final CoordinationImpl c) throws OpenDataException
    {
        return new CompositeDataSupport(COORDINATION_TYPE, new String[]
            { ID, NAME, TIMEOUT }, new Object[]
            { c.getId(), c.getName(), c.getDeadLine() });
    }
    */

	public Coordination getEnclosingCoordination(final CoordinationImpl c)
	{
        Stack<Coordination> stack = threadStacks.get();
        if ( stack != null )
        {
        	final int index = stack.indexOf(c);
        	if ( index > 0 )
        	{
        		return stack.elementAt(index - 1);
        	}
        }
		return null;
	}

	public boolean endNestedCoordinations(final CoordinationImpl c)
	{
	    boolean partiallyFailed = false;
        Stack<Coordination> stack = threadStacks.get();
        if ( stack != null )
        {
        	final int index = stack.indexOf(c) + 1;
        	if ( index > 0 && stack.size() > index )
        	{
        		final int count = stack.size()-index;
        		for(int i=0;i<count;i++)
        		{
        			final Coordination nested = stack.pop();
        			try
        			{
        			    nested.end();
        			}
        			catch ( final CoordinationException ce)
        			{
        			    partiallyFailed = true;
        			}
        		}
        	}
        }
        return partiallyFailed;
	}

	/**
	 * Dispose all coordinations for that bundle
	 * @param owner The owner bundle
	 */
    public void dispose(final Bundle owner) {
        final List<CoordinationImpl> candidates = new ArrayList<CoordinationImpl>();
        synchronized ( this.coordinations )
        {
            final Iterator<Map.Entry<Long, CoordinationImpl>> iter = this.coordinations.entrySet().iterator();
            while ( iter.hasNext() )
            {
                final Map.Entry<Long, CoordinationImpl> entry = iter.next();
                if ( entry.getValue().getBundle().getBundleId() == owner.getBundleId() )
                {
                    candidates.add(entry.getValue());
                }
            }
        }
        if ( candidates.size() > 0 )
        {
            for(final CoordinationImpl c : candidates)
            {
                try {
                if ( !c.isTerminated() )
                {
                    c.fail(Coordination.RELEASED);
                }
                else
                {
                    this.unregister(c, true);
                }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
