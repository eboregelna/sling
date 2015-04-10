package org.apache.sling.security.impl;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.util.Dictionary;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.security.ContentDispositionServletConfiguration;
import org.osgi.service.component.ComponentContext;

@Component(metatype = true,
description = "Content Disposition filter configuration for white listing servlets",
label=" Apache Sling Content Disposition Filter Servlet WhiteList", immediate=true, configurationFactory=true)
@Service(value = ContentDispositionServletConfiguration.class)
public class ContentDispositionServletConfigurationImpl implements ContentDispositionServletConfiguration {

    @Property(label = "Content Disposition Servlet Whitelist", 
            description = "Entries are of one of the forms: leaf, parent/leaf, or parent/*. " +
                    "Invalid entries are logged and ignored."
                    , unbounded = PropertyUnbounded.ARRAY, value = { "" })
    private static final String PROP_SERVLET_WHITELIST = "sling.content.disposition.servlet.whitelist";

    private String[] servletWhitelist = new String[0];
    
    @Activate
    private void activate(final ComponentContext ctx) {
        final Dictionary props = ctx.getProperties();

        servletWhitelist = PropertiesUtil.toStringArray(props.get(PROP_SERVLET_WHITELIST));
    }
    
    public String[] getWhitelistedServlets() {
        return servletWhitelist;
    }

}
