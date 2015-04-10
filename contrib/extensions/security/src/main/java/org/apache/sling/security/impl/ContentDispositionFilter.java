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
package org.apache.sling.security.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.security.ContentDispositionServletConfiguration;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(metatype = true,
description = "Request filter adding Content Disposition attachment for certain paths/content types",
label=" Apache Sling Content Disposition Filter")
@Service(value = Filter.class)
@Properties({
        @Property(name = "sling.filter.scope", value = { "request" }, propertyPrivate = true),
        @Property(name = "service.ranking", intValue = -25000, propertyPrivate = true) })
@Reference(referenceInterface = ContentDispositionServletConfiguration.class,
        cardinality=ReferenceCardinality.OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        bind="bindServletConfiguration", unbind="unbindServletConfiguration")
public class ContentDispositionFilter implements Filter {
    
    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    @Property(label = "Content Disposition Paths", 
            description = "These paths are filtered by the filter. "+
                    "Each entry is of the form 'path [ \":\" CSV of excluded content types ]'. " +
                    "Invalid entries are logged and ignored."
                    , unbounded = PropertyUnbounded.ARRAY, value = { "" })
    private static final String PROP_CONTENT_DISPOSTION_PATHS = "sling.content.disposition.paths";
   
    private ArrayList<ContentDispositionServletConfiguration> servletConfigurations =
    new ArrayList<ContentDispositionServletConfiguration>();
    
    /**
     * Set of paths
     */
    Set<String> contentDispositionPaths;

    /**
     * Array of prefixes of paths
     */
    private String[] contentDispositionPathsPfx;
    
    /**
     * Set of whitelisted destination servlets
     */
    volatile Set<String> contentDispositionServletWhitelist;
    /**
     * Array of prefixes of servlets
     */
    volatile private String[] contentDispositionServletWhitelistPfx;
    
    /**
     * Only update the servlet whitelist values after an update.
     */
    volatile boolean servletConfigurationsInvalid = true;

    private Map<String, Set<String>> contentTypesMapping;

    @Activate
    private void activate(final ComponentContext ctx) {
        final Dictionary props = ctx.getProperties();

        updateServletConfiguration();
        
        String[] contentDispostionProps = PropertiesUtil.toStringArray(props.get(PROP_CONTENT_DISPOSTION_PATHS));
        
        Set<String> paths = new HashSet<String>();
        List<String> pfxs = new ArrayList<String>();
        Map<String, Set<String>> contentTypesMap = new HashMap<String, Set<String>>();
        
        for (String path : contentDispostionProps) {
            path = path.trim();
            if (path.length() > 0) {
                int idx = path.indexOf('*');
                int colonIdx = path.indexOf(":");
                
                if (colonIdx > -1 && colonIdx < idx) {
                    // ':'  in paths is not allowed
                    logger.info("':' in paths is not allowed.");
                } else {
                    String p = null;
                    if (idx >= 0) {
                        if (idx > 0) {
                            p = path.substring(0, idx);
                            pfxs.add(p);
                        } else {
                            // we don't allow "*" - that would defeat the
                            // purpose.
                            logger.info("catch-all wildcard for paths not allowed.");
                        }
                    } else {
                        if (colonIdx > -1) {
                            p = path.substring(0, colonIdx);
                        } else {
                            p = path;
                        }
                        paths.add(p);
                    }                   
                    if (colonIdx != -1 && p != null) {
                        Set <String> contentTypes = getContentTypes(path.substring(colonIdx+1));
                        contentTypesMap.put(p, contentTypes);
                    }
                }

            }
        }
        
        contentDispositionPaths = paths.isEmpty() ? Collections.<String>emptySet() : paths;
        contentDispositionPathsPfx = pfxs.toArray(new String[pfxs.size()]);
        contentTypesMapping = contentTypesMap.isEmpty()?Collections.<String, Set<String>>emptyMap(): contentTypesMap;
        
        logger.info("Initialized. content disposition paths: {}, content disposition paths-pfx {}", new Object[]{
                contentDispositionPaths, contentDispositionPathsPfx});
    }
    

    public void init(FilterConfig filterConfig) throws ServletException {
        // nothing to do
    }

    public void destroy() {
        // nothing to do
    }

    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
  
        final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;

        final RewriterResponse rewriterResponse = new RewriterResponse(slingRequest, slingResponse);

        chain.doFilter(request, rewriterResponse);
    }


    //---------- PRIVATE METHODS ---------
    
    private static Set<String> getContentTypes(String contentTypes) {
        Set<String> contentTypesSet = new HashSet<String>();
        if (contentTypes != null && contentTypes.length() > 0) {
            String[] contentTypesArray = contentTypes.split(",");
            for (String contentType : contentTypesArray) {
                contentTypesSet.add(contentType);
            }
        }
        return contentTypesSet;
    }

    public void bindServletConfiguration(ContentDispositionServletConfiguration config) {
        synchronized(servletConfigurations) {
            servletConfigurations.add(config);
            servletConfigurationsInvalid = true;
        }
    }

    public void unbindServletConfiguration(ContentDispositionServletConfiguration config) {
        synchronized(servletConfigurations) {
            servletConfigurations.remove(config);
            servletConfigurationsInvalid = true;
        }
    }
    
    private void updateServletConfiguration() {
        synchronized (servletConfigurations) {
            if (!servletConfigurationsInvalid) {
                return;
            }
            Set<String> servlets = new HashSet<String>();
            List<String> servletPrefixes = new ArrayList<String>();

            for (ContentDispositionServletConfiguration config: servletConfigurations) {
                for (String path : config.getWhitelistedServlets() != null ?
                    config.getWhitelistedServlets() : new String[] {}) {
                    path = path.trim();
                    if (path.length() > 0) {
                        if (path.endsWith("*")) {
                            String prefix = path.substring(0, path.length() - 1);
                            if (prefix.length() > 0) {
                                servletPrefixes.add(prefix);
                            } else {
                                logger.info("Catch all servlet whitelisting not allowed.");
                            }
                        } else {
                            servlets.add(path);
                        }
                    }
                }
            }
            
            /*
             * Explicitly accepting that the checking of the whitelists *could* be seen as inconsistent.
             * One or the other could represent an older or newer state of the configuration than the other.
             */
            contentDispositionServletWhitelist = servlets.isEmpty() ? Collections.<String>emptySet() : servlets;
            contentDispositionServletWhitelistPfx = servletPrefixes.toArray(new String[servletPrefixes.size()]);
            logger.info("servlet whitelist {}, servlet whitelist by prefix {}", new Object[]{
                contentDispositionServletWhitelist, contentDispositionServletWhitelistPfx});
            servletConfigurationsInvalid = false;
        }
    }
    
    //----------- INNER CLASSES ------------ 

    protected class RewriterResponse extends SlingHttpServletResponseWrapper {
        
        private static final String CONTENT_DISPOSTION = "Content-Disposition";

        private static final String CONTENT_DISPOSTION_ATTACHMENT = "attachment";
        
        static final String CONTENT_TYPE_ATTRIBUTE =
                "org.apache.sling.security.impl.ContentDispositionFilter.RewriterResponse.contentType";
        static final String WHITELISTED_ATTRIBUTE =
                "org.apache.sling.security.impl.ContentDispositionFilter.RewriterResponse.whiteListed";
        
        /** The current request. */
        private final SlingHttpServletRequest request;

        public RewriterResponse(SlingHttpServletRequest request, SlingHttpServletResponse wrappedResponse) {
            super(wrappedResponse);            
            this.request = request;
        }
        
        /**
         * @see javax.servlet.ServletResponseWrapper#setContentType(java.lang.String)
         */
        public void setContentType(String type) { 
            // once whitelisted by servlet, always whitelisted
            if (request.getAttribute(WHITELISTED_ATTRIBUTE) != null) {
                passThru(type);
                return;
            }
            String previousContentType = (String) request.getAttribute(CONTENT_TYPE_ATTRIBUTE);
            
            if (previousContentType != null) {
                if(previousContentType.equals(type)) {
                    passThru(type);
                    return;
                } else {
                    int idx = type.indexOf(';');
                    if (idx >= 0) {
                        if (previousContentType.equals(type.substring(0, idx))) {
                            passThru(type);
                            return;
                        }
                    }
                }
            }
            
            String servletName = (String)request.getAttribute(SlingConstants.SLING_CURRENT_SERVLET_NAME);
            updateServletConfiguration();
            if (contentDispositionServletWhitelist.contains(servletName)) {
                whiteListByServlet();
                passThru(type);
                return;
            }
            for (String servletPrefix : contentDispositionServletWhitelistPfx) {
                if (servletName.startsWith(servletPrefix)) {
                    whiteListByServlet();
                    passThru(type);
                    return;
                }
            }

            request.setAttribute(CONTENT_TYPE_ATTRIBUTE, type);
            
            String pathInfo = request.getPathInfo();
            if (contentDispositionPaths.contains(pathInfo)) {

                if (contentTypesMapping.containsKey(pathInfo)) {
                    Set exceptions = contentTypesMapping.get(pathInfo);
                    if (!exceptions.contains(type)) {
                        setContentDisposition();
                    }
                } else {
                    setContentDisposition();
                }
            }
            
            for (String path : contentDispositionPathsPfx) {
                if (request.getPathInfo().startsWith(path)) {
                    if (contentTypesMapping.containsKey(path)) {
                        Set exceptions = contentTypesMapping.get(path);
                        if (!exceptions.contains(type)) {
                            setContentDisposition();
                            break;
                        }
                    } else {
                        setContentDisposition();
                        break;
                    }

                }
            }
            super.setContentType(type);
        }    
        
        private void whiteListByServlet() {
            request.setAttribute(WHITELISTED_ATTRIBUTE, WHITELISTED_ATTRIBUTE);
            
        }

        private void passThru(String type) {
            super.setContentType(type);
            
        }

        private void setContentDisposition() {
            if (!this.containsHeader(CONTENT_DISPOSTION)) {
                this.addHeader(CONTENT_DISPOSTION, CONTENT_DISPOSTION_ATTACHMENT);
            }
        }
    }
}
