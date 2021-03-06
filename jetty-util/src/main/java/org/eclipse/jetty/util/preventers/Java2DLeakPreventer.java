//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.preventers;

/**
 * Java2DLeakPreventer
 *
 * Prevent pinning of webapp classloader by pre-loading sun.java2d.Disposer class
 * before webapp classloaders are created.
 *
 * See https://issues.apache.org/bugzilla/show_bug.cgi?id=51687
 */
public class Java2DLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class.forName("sun.java2d.Disposer", true, loader);
        }
        catch (ClassNotFoundException e)
        {
            LOG.trace("IGNORED", e);
        }
    }
}
