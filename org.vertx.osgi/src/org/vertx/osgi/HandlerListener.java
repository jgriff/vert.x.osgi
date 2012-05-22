/*
 * Copyright 2012 the original author or authors.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.osgi;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.sockjs.SockJSServer;
import org.vertx.java.core.sockjs.SockJSSocket;

final class HandlerListener {

    private Vertx vertx;

    private BundleContext bundleContext;

    private ServiceTrackerCustomizer<Handler<?>, Handler<?>> handlerTracker;

    private ServiceTracker<Handler<?>, Handler<?>> serviceTracker;

    private final ConcurrentMap<Integer, HttpServer> servers = new ConcurrentHashMap<Integer, HttpServer>();

    public void start(BundleContext bundleContext, Vertx vertx) {
        this.bundleContext = bundleContext;
        this.vertx = vertx;
        registerHandlerListener();
        scanForExistingHandlers();
    }

    private void registerHandlerListener() {
        this.handlerTracker = new HandlerTracker();
        this.serviceTracker = new ServiceTracker<Handler<?>, Handler<?>>(this.bundleContext, Handler.class.getName(), this.handlerTracker);
        this.serviceTracker.open();
    }

    public void stop() {
        this.serviceTracker.close();
        for (int portNumber : this.servers.keySet()) {
            try {
                deregisterHandler(portNumber);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void scanForExistingHandlers() {
        try {
            ServiceReference<?>[] allServiceReferences = this.bundleContext.getAllServiceReferences("org.vertx.java.core.Handler", null);
            if (allServiceReferences != null) {
                for (ServiceReference<?> serviceReference : allServiceReferences) {
                    registerHandlerIfNecessary(serviceReference);
                }
            }
        } catch (InvalidSyntaxException e) {
            e.printStackTrace();
        }
    }

    private void registerHandlerIfNecessary(ServiceReference<?> serviceReference) {
        @SuppressWarnings({ "unchecked" })
        ServiceReference<Handler<?>> handlerServiceReference = (ServiceReference<Handler<?>>) serviceReference;
        switch (getHandlerType(handlerServiceReference)) {
            case "HttpServerRequestHandler": {
                int portNumber = getHandlerPortNumber(handlerServiceReference);
                if (portNumber != 0) {
                    String usage = getHandlerUsage(handlerServiceReference);
                    if ("SockJS".equals(usage)) {
                        sockJSHandlerAdded(portNumber, handlerServiceReference, null);
                    } else {
                        @SuppressWarnings("unchecked")
                        Handler<HttpServerRequest> handler = (Handler<HttpServerRequest>) this.bundleContext.getService(handlerServiceReference);
                        try {
                            HttpServer httpServer = registerHandler(portNumber, handler);
                            listen(httpServer, portNumber);
                        } finally {
                            this.bundleContext.ungetService(serviceReference);
                        }
                    }
                }
            }
                break;
            case "SockJSHandler": {
                int portNumber = getHandlerPortNumber(handlerServiceReference);
                if (portNumber != 0) {
                    sockJSHandlerAdded(portNumber, null, handlerServiceReference);
                }
            }
                break;
            default:
                ;
        }
    }

    /*
     * The following method is called when either a HTTP handler (for use with SockJS) or a SockJS handler has been
     * added. Null is typically passed in as the other service reference. If there is a matching pair, then a suitable
     * server is created and set up. If either or both are missing, then there is no change of state.
     */
    @SuppressWarnings("unchecked")
    private void sockJSHandlerAdded(int portNumber, ServiceReference<Handler<?>> httpHandlerServiceReference,
        ServiceReference<Handler<?>> sockJSHandlerServiceReference) {

        Set<ServiceReference<?>> sockJSHandlerRefs = findHandlerRefs(portNumber, "(type=SockJSHandler)");
        if (sockJSHandlerServiceReference != null) {
            sockJSHandlerRefs.add(sockJSHandlerServiceReference);
        }

        Set<ServiceReference<?>> httpHandlerRefs = findHandlerRefs(portNumber, "(&(type=HttpServerRequestHandler)(usage=SockJS))");
        if (httpHandlerServiceReference != null) {
            httpHandlerRefs.add(httpHandlerServiceReference);
        }

        if (sockJSHandlerRefs.size() == 1 && httpHandlerRefs.size() > 1) {
            // Ambiguous HTTP handler
        } else if (httpHandlerRefs.size() == 1 && sockJSHandlerRefs.size() > 1) {
            // Ambiguous SockJS handler
        } else if (httpHandlerRefs.size() == 1 && sockJSHandlerRefs.size() == 1) {
            // One of each - proceed
            ServiceReference<?> sRef = sockJSHandlerRefs.iterator().next();
            ServiceReference<Handler<SockJSSocket>> sockJSRef = (ServiceReference<Handler<SockJSSocket>>) sRef;

            ServiceReference<?> hRef = httpHandlerRefs.iterator().next();
            ServiceReference<Handler<HttpServerRequest>> httpRef = (ServiceReference<Handler<HttpServerRequest>>) hRef;

            Handler<SockJSSocket> sockJSHandler = (Handler<SockJSSocket>) this.bundleContext.getService(sockJSRef);
            Handler<HttpServerRequest> httpHandler = (Handler<HttpServerRequest>) this.bundleContext.getService(httpRef);
            try {
                HttpServer httpServer = registerHandler(portNumber, httpHandler);

                SockJSServer sockServer = this.vertx.createSockJSServer(httpServer);

                sockServer.installApp(
                    new JsonObject().putString("prefix", getHandlerPrefix((ServiceReference<Handler<?>>) (ServiceReference<?>) sockJSRef)),
                    sockJSHandler);

                listen(httpServer, portNumber);
            } finally {
                this.bundleContext.ungetService(sockJSRef);
                this.bundleContext.ungetService(httpRef);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private Set<ServiceReference<?>> findHandlerRefs(int portNumber, String filter) {
        Set<ServiceReference<?>> result = new HashSet<ServiceReference<?>>();
        try {
            Collection<ServiceReference<Handler>> refs = this.bundleContext.getServiceReferences(Handler.class, filter);
            for (ServiceReference<Handler> ref : refs) {
                if (portNumber == getHandlerPortNumber(ref)) {
                    result.add((ServiceReference<?>) ref);
                }
            }
        } catch (InvalidSyntaxException e) {
            // Should never happen
            e.printStackTrace();
        }
        return result;
    }

    private String getHandlerUsage(ServiceReference<Handler<?>> handlerServiceReference) {
        Object handlerUsage = handlerServiceReference.getProperty("usage");
        return handlerUsage instanceof String ? (String) handlerUsage : null;
    }

    private void listen(HttpServer httpServer, int portNumber) {
        try {
            httpServer.listen(portNumber);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getHandlerType(ServiceReference<Handler<?>> serviceReference) {
        Object handlerType = serviceReference.getProperty("type");
        return handlerType instanceof String ? (String) handlerType : null;
    }

    private int getHandlerPortNumber(ServiceReference<?> handlerServiceReference) {
        int portNumber = 0;
        Object port = handlerServiceReference.getProperty("port");
        if (port instanceof String) {
            portNumber = Integer.parseInt((String) port);
        }
        return portNumber;
    }

    private String getHandlerPrefix(ServiceReference<Handler<?>> serviceReference) {
        Object handlerPrefix = serviceReference.getProperty("prefix");
        return handlerPrefix instanceof String ? (String) handlerPrefix : null;
    }

    private HttpServer registerHandler(int portNumber, Handler<HttpServerRequest> handler) {
        HttpServer httpServer = getHttpServer(portNumber);
        try {
            if (handler != null) {
                httpServer.requestHandler(handler);
            }
            return httpServer;
        } catch (Exception e) {
            return null;
        }
    }

    private void deregisterHandler(int portNumber) {
        HttpServer httpServer = findHttpServer(portNumber);
        if (httpServer != null) {
            httpServer.close();
            servers.remove(portNumber);
        }
    }

    private HttpServer getHttpServer(int portNumber) {
        HttpServer httpServer = this.servers.get(portNumber);
        if (httpServer == null) {
            httpServer = this.vertx.createHttpServer();
            HttpServer existingHttpServer = this.servers.putIfAbsent(portNumber, httpServer);
            if (existingHttpServer != null) {
                httpServer = existingHttpServer;
            }
        }
        return httpServer;
    }

    private HttpServer findHttpServer(int portNumber) {
        return servers.get(portNumber);
    }

    private final class HandlerTracker implements ServiceTrackerCustomizer<Handler<?>, Handler<?>> {

        @Override
        public Handler<?> addingService(ServiceReference<Handler<?>> serviceReference) {
            Handler<?> handler = bundleContext.getService(serviceReference);
            registerHandlerIfNecessary(serviceReference);
            return handler;
        }

        @Override
        public void modifiedService(ServiceReference<Handler<?>> serviceReference, Handler<?> handler) {
        }

        @Override
        public void removedService(ServiceReference<Handler<?>> serviceReference, Handler<?> handler) {
            unregisterHandlerIfNecessary(serviceReference);
        }

        private void unregisterHandlerIfNecessary(ServiceReference<Handler<?>> serviceReference) {
            ServiceReference<Handler<?>> handlerServiceReference = (ServiceReference<Handler<?>>) serviceReference;
            if ("HttpServerRequestHandler".equals(getHandlerType(handlerServiceReference))) {
                int portNumber = getHandlerPortNumber(handlerServiceReference);
                if (portNumber != 0) {
                    deregisterHandler(portNumber);
                }
            }
        }

    }

}
