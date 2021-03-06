/*******************************************************************************
 * Copyright (c) 2020 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.jaxrs21.client.callback.server;

import java.util.HashSet;
import java.util.Set;

public class JAXRS21MyApplication extends javax.ws.rs.core.Application {

	@Override
	public Set<Class<?>> getClasses() {
	
		Set<Class<?>> set=new HashSet<Class<?>>();
		set.add(JAXRS21MyResource.class);
		return set;
	}

}
