/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.javaee.dd.commonbnd;

import com.ibm.ws.javaee.ddmetadata.annotation.DDAttribute;
import com.ibm.ws.javaee.ddmetadata.annotation.DDAttributeType;
import com.ibm.ws.javaee.ddmetadata.annotation.DDIdAttribute;

/**
 * Represents &lt;env-entry>.
 */
@DDIdAttribute
public interface EnvEntry {

    @DDAttribute(name = "name", type = DDAttributeType.String)
    String getName();

    @DDAttribute(name = "value", type = DDAttributeType.String)
    String getValue();

    @DDAttribute(name = "binding-name", type = DDAttributeType.String)
    String getBindingName();

}
